package com.factory.domino.runner;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads an application jar child-first, so the jar's own copy of a class wins over the
 * launcher's, while a fixed set of packages stays shared with the parent loader.
 *
 * <p>The selective part is not a refinement, it is what makes this safe. A pure parent-last
 * loader would happily load a second copy of {@code com.hcl.domino.*} and {@code com.sun.jna.*}
 * out of the application jar. That breaks in two ways at once: JNA would try to bind native
 * libraries that are already bound in this process, and any object handed across the two loaders
 * would raise {@link ClassCastException} between two same-named but distinct classes. Both
 * failure modes surface as native crashes rather than Java errors, so the shared packages are
 * delegated to the parent unconditionally.
 *
 * <p>The jar is read directly from disk; nothing is copied into a temporary location. Classes
 * are defined with the jar as their {@link java.security.CodeSource}, which preserves signature
 * information for callers that check it.
 *
 * <p>Note on isolation: this class provides <em>separation</em>, not a security sandbox. Since
 * {@code SecurityManager} was deprecated (JEP 411) and disabled in recent JDKs, a JVM cannot
 * restrict what loaded code is allowed to do. Hosted code runs with the same privileges as the
 * launcher; only class visibility is isolated.
 */
public final class IsolatedJarClassLoader extends URLClassLoader {

  /**
   * Packages always loaded by the parent, never from the application jar.
   *
   * <p>The JDK-owned prefixes are non-negotiable. The Domino ones are what keep a single native
   * runtime in the process. {@code com.factory.domino.runner} is shared so hosted code can talk
   * to the launcher through the same classes the launcher itself uses.
   */
  private static final List<String> DEFAULT_SHARED_PREFIXES = List.of(
      "java.",
      "javax.",
      "jdk.",
      "sun.",
      "com.sun.",
      "org.w3c.dom.",
      "org.xml.sax.",
      "org.omg.",
      // Domino runtime: exactly one copy per process, or the native layer breaks.
      "com.hcl.domino.",
      "com.sun.jna.",
      "lotus.domino.",
      "lotus.notes.",
      // The launcher's own API, so hosted code and launcher agree on types.
      "com.factory.domino.runner."
  );

  private final Set<String> sharedPrefixes;
  private final File jarFile;

  static {
    registerAsParallelCapable();
  }

  private IsolatedJarClassLoader(URL[] urls, ClassLoader parent, Set<String> sharedPrefixes,
      File jarFile) {
    super(urls, parent);
    this.sharedPrefixes = sharedPrefixes;
    this.jarFile = jarFile;
  }

  /**
   * Creates a loader for the given application jar.
   *
   * @param jar the application jar, read in place from disk
   * @param parent the loader holding the Domino runtime and launcher classes
   * @param additionalSharedPrefixes extra package prefixes to delegate to the parent
   */
  public static IsolatedJarClassLoader forJar(File jar, ClassLoader parent,
      List<String> additionalSharedPrefixes) throws MalformedURLException {
    if (!jar.isFile()) {
      throw new IllegalArgumentException("application jar does not exist: " + jar);
    }
    Set<String> prefixes = new LinkedHashSet<>(DEFAULT_SHARED_PREFIXES);
    if (additionalSharedPrefixes != null) {
      prefixes.addAll(additionalSharedPrefixes);
    }
    return new IsolatedJarClassLoader(new URL[] {jar.toURI().toURL()}, parent, prefixes, jar);
  }

  /** Reads {@code Main-Class} from the jar manifest, or {@code null} when absent. */
  public String getManifestMainClass() throws IOException {
    try (JarFile jar = new JarFile(jarFile)) {
      Manifest manifest = jar.getManifest();
      if (manifest == null) {
        return null;
      }
      return manifest.getMainAttributes().getValue("Main-Class");
    }
  }

  public File getJarFile() {
    return jarFile;
  }

  private boolean isShared(String className) {
    for (String prefix : sharedPrefixes) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);

      if (loaded == null) {
        if (isShared(name)) {
          // Shared package: parent owns it, and a missing class here is a real error.
          loaded = super.loadClass(name, false);
        } else {
          // Child-first: prefer the application jar, fall back to the parent.
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException e) {
            loaded = super.loadClass(name, false);
          }
        }
      }

      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  @Override
  public URL getResource(String name) {
    // Resources follow the same rule as classes, so a bundled config does not silently
    // lose to one on the launcher's classpath.
    String asClassName = name.replace('/', '.');
    if (!isShared(asClassName)) {
      URL own = findResource(name);
      if (own != null) {
        return own;
      }
    }
    return super.getResource(name);
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    // Order matters for ServiceLoader: the application jar's entries come first.
    List<URL> combined = new ArrayList<>();
    for (Enumeration<URL> own = findResources(name); own.hasMoreElements(); ) {
      combined.add(own.nextElement());
    }
    ClassLoader parent = getParent();
    if (parent != null) {
      for (Enumeration<URL> fromParent = parent.getResources(name); fromParent.hasMoreElements(); ) {
        URL url = fromParent.nextElement();
        if (!combined.contains(url)) {
          combined.add(url);
        }
      }
    }
    return Collections.enumeration(combined);
  }
}
