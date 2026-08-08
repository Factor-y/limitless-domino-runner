package com.factory.domino.browser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Publishes the web application over mDNS/Bonjour so it can be found without knowing the host's
 * IP address.
 *
 * <p>One detail is worth stating plainly, because it decides what actually works: mDNS only
 * resolves names in the <strong>{@code .local}</strong> domain (RFC 6762). A name such as
 * {@code domino.browser} cannot be resolved by mDNS at all, no matter how it is registered — no
 * resolver will query multicast DNS for it. So this class does two different things with that
 * name:
 *
 * <ul>
 *   <li>registers the <em>service instance</em> under the requested name, which is what shows up
 *       in Bonjour service browsers and in Safari's Bonjour bookmarks;
 *   <li>registers a resolvable <em>host name</em> derived from it — {@code domino.browser}
 *       becomes {@code domino-browser.local} — which is what you can actually type into a
 *       browser.
 * </ul>
 *
 * <p>The address advertised is the one the server is bound to. Bound to loopback, as it is by
 * default, the name resolves to 127.0.0.1: usable on this machine, useless from another. Binding
 * to a LAN address is what makes discovery meaningful, and that is a deliberate choice to make
 * rather than a default to inherit.
 */
public final class MdnsAnnouncer implements AutoCloseable {

  private static final String SERVICE_TYPE = "_http._tcp.local.";

  private final JmDNS jmdns;
  private final ServiceInfo serviceInfo;
  private final String hostName;

  private MdnsAnnouncer(JmDNS jmdns, ServiceInfo serviceInfo, String hostName) {
    this.jmdns = jmdns;
    this.serviceInfo = serviceInfo;
    this.hostName = hostName;
  }

  /**
   * Announces the application on the network.
   *
   * @param serviceName the advertised service name, e.g. {@code domino.browser}
   * @param bindHost the address the server is bound to, or {@code 0.0.0.0} for all interfaces
   * @param port the HTTP port
   * @return the announcer, or {@code null} if mDNS could not be started — discovery is a
   *     convenience, so failing to announce must never prevent the server from serving
   */
  public static MdnsAnnouncer announce(String serviceName, String bindHost, int port) {
    try {
      InetAddress address = resolveAdvertisedAddress(bindHost);

      // mDNS host names live in .local; anything else is unresolvable, so the requested
      // name is converted into a valid one.
      String hostName = toMdnsHostName(serviceName);

      JmDNS jmdns = JmDNS.create(address, hostName);

      Map<String, String> properties = new HashMap<>();
      properties.put("path", "/");
      properties.put("api", "/openapi.json");
      properties.put("docs", "/swagger");

      ServiceInfo serviceInfo = ServiceInfo.create(
          SERVICE_TYPE, serviceName, port, 0, 0, properties);
      jmdns.registerService(serviceInfo);

      System.out.println("  mDNS: announced as \"" + serviceName + "\" on " + SERVICE_TYPE);
      System.out.println("  mDNS: reachable at http://" + hostName + ".local:" + port + "/"
          + "  (mDNS resolves .local names only)");
      if (address.isLoopbackAddress()) {
        System.out.println("  mDNS: bound to loopback, so this name resolves to "
            + address.getHostAddress() + " and is only reachable from this machine."
            + " Use --host 0.0.0.0 to expose it on the network.");
      }
      return new MdnsAnnouncer(jmdns, serviceInfo, hostName);
    } catch (IOException | RuntimeException e) {
      System.err.println("[domino-browser] mDNS registration failed (" + e
          + "); the server is unaffected.");
      return null;
    }
  }

  /**
   * Turns a requested service name into a valid mDNS host label: lowercase, with anything that
   * is not a letter, digit or hyphen replaced by a hyphen. {@code domino.browser} becomes
   * {@code domino-browser}, which resolves as {@code domino-browser.local}.
   */
  static String toMdnsHostName(String serviceName) {
    String label = serviceName.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9-]", "-")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
    return label.isEmpty() ? "domino-browser" : label;
  }

  /**
   * Picks the address to advertise. A wildcard bind has no single address of its own, so the
   * first non-loopback address of an up interface is used — that is the one other machines can
   * actually reach.
   */
  private static InetAddress resolveAdvertisedAddress(String bindHost)
      throws UnknownHostException, SocketException {
    if (bindHost != null && !bindHost.isEmpty()
        && !"0.0.0.0".equals(bindHost) && !"::".equals(bindHost)) {
      return InetAddress.getByName(bindHost);
    }
    for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
        continue;
      }
      for (InetAddress candidate : Collections.list(nic.getInetAddresses())) {
        if (candidate instanceof java.net.Inet4Address && !candidate.isLoopbackAddress()) {
          return candidate;
        }
      }
    }
    return InetAddress.getLocalHost();
  }

  /** The resolvable {@code .local} host name, without the domain suffix. */
  public String getHostName() {
    return hostName;
  }

  @Override
  public void close() {
    try {
      jmdns.unregisterService(serviceInfo);
      jmdns.close();
    } catch (IOException | RuntimeException e) {
      System.err.println("[domino-browser] error shutting down mDNS: " + e);
    }
  }
}
