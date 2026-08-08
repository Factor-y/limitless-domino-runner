package com.factory.domino.browser;

import java.util.LinkedHashMap;
import java.util.Map;

import com.factory.domino.runner.DominoExecutor;
import com.factory.domino.runner.RunnerLifecycle;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

/**
 * A read-only web front end for browsing Domino databases, launched by {@code DominoRunner}.
 *
 * <p>Run it with:
 * <pre>
 *   domino-runner-macos.sh --jar domino-browser.jar --wait
 * </pre>
 *
 * <p>{@code main} returns as soon as the listener is up — the runner's {@code --wait} keeps the
 * process alive, and the cleanup registered with {@link RunnerLifecycle} stops the server before
 * the Domino runtime is released.
 *
 * <p>The server binds to the loopback interface only. Everything it exposes is readable with the
 * runner's Notes identity, so it is not something to put on a network interface without
 * authentication in front of it.
 */
public final class DominoBrowser {

  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_DOMINO_THREADS = 4;
  private static final String DEFAULT_MDNS_NAME = "domino.browser";

  /** Must match the org.webjars:swagger-ui version in the POM: it is part of the resource path. */
  private static final String SWAGGER_UI_VERSION = "5.25.3";

  public static void main(String[] args) {
    int port = DEFAULT_PORT;
    String host = DEFAULT_HOST;
    int dominoThreads = DEFAULT_DOMINO_THREADS;
    String mdnsName = DEFAULT_MDNS_NAME;
    boolean mdnsEnabled = true;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> port = Integer.parseInt(args[++i]);
        case "--host" -> host = args[++i];
        case "--domino-threads" -> dominoThreads = Integer.parseInt(args[++i]);
        case "--mdns-name" -> mdnsName = args[++i];
        case "--no-mdns" -> mdnsEnabled = false;
        default -> {
          System.err.println("domino-browser: unknown option '" + args[i] + "'");
          System.err.println("Usage: DominoBrowser [--port <port>] [--host <host>] "
              + "[--domino-threads <n>] [--mdns-name <name>] [--no-mdns]");
          return;
        }
      }
    }

    // Domino work never runs on a Jetty thread: it is handed to workers that hold a live
    // Domino context. See DominoExecutor for why this is not optional.
    DominoExecutor executor = new DominoExecutor(dominoThreads);
    DominoService service = new DominoService(executor);

    Javalin app = Javalin.create(config -> {
      config.staticFiles.add(staticFiles -> {
        staticFiles.hostedPath = "/";
        staticFiles.directory = "/web";
        staticFiles.location = Location.CLASSPATH;
      });
      // Swagger UI assets straight out of the webjar. The version is part of the path,
      // which is why SWAGGER_UI_VERSION has to track the POM.
      config.staticFiles.add(staticFiles -> {
        staticFiles.hostedPath = "/swagger-ui";
        staticFiles.directory = "/META-INF/resources/webjars/swagger-ui/" + SWAGGER_UI_VERSION;
        staticFiles.location = Location.CLASSPATH;
      });
      config.showJavalinBanner = false;
    });

    registerRoutes(app, service);
    registerErrorHandling(app);

    app.start(host, port);

    MdnsAnnouncer mdns = mdnsEnabled ? MdnsAnnouncer.announce(mdnsName, host, port) : null;

    // Ordered teardown: mDNS and server first, then Domino workers, then the runner releases
    // the runtime. A plain JVM shutdown hook would race that last step.
    RunnerLifecycle.onShutdown(() -> {
      System.out.println("[domino-browser] stopping server...");
      if (mdns != null) {
        mdns.close();
      }
      app.stop();
      executor.close();
      System.out.println("[domino-browser] stopped.");
    });

    System.out.println();
    System.out.println("  Domino Browser ready at http://" + host + ":" + port + "/");
    System.out.println("  API documentation:      http://" + host + ":" + port + "/swagger/");
    System.out.println("  Domino worker threads: " + executor.getThreadCount());
    System.out.println();
  }

  private static void registerRoutes(Javalin app, DominoService service) {
    // Server and database are query parameters rather than path segments on purpose:
    // Domino paths contain '/' and '\' for subdirectories, which would need fragile
    // double-encoding as path segments.

    // 1. Databases on a server (empty server = local). Recursive by default: a flat listing
    // hides everything in subdirectories, which looks like missing databases.
    app.get("/api/databases", ctx ->
        ctx.json(service.listDatabases(ctx.queryParam("server"),
            !"false".equalsIgnoreCase(ctx.queryParam("recursive")))));

    // 2. Database details
    app.get("/api/database", ctx ->
        ctx.json(service.getDatabaseDetails(ctx.queryParam("server"), requiredDb(ctx))));

    // 3. Views
    app.get("/api/database/views", ctx ->
        ctx.json(service.listViews(ctx.queryParam("server"), requiredDb(ctx))));

    // 4. Forms
    app.get("/api/database/forms", ctx ->
        ctx.json(service.listForms(ctx.queryParam("server"), requiredDb(ctx))));

    // 4b. A single form's design: fields, properties, formulas, events, subforms
    app.get("/api/database/form", ctx ->
        ctx.json(service.getFormDetails(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "form"))));

    // 5. Document by note ID or UNID
    app.get("/api/document", ctx ->
        ctx.json(service.getDocument(ctx.queryParam("server"), requiredDb(ctx),
            ctx.queryParam("noteid"), ctx.queryParam("unid"))));

    // 6. Named documents
    app.get("/api/document/named", ctx ->
        ctx.json(service.getNamedDocument(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "name"), ctx.queryParam("username"))));
    app.get("/api/documents/named", ctx ->
        ctx.json(service.listNamedDocuments(ctx.queryParam("server"), requiredDb(ctx))));

    // 7. Profile documents
    app.get("/api/document/profile", ctx ->
        ctx.json(service.getProfileDocument(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "profile"), ctx.queryParam("username"))));

    // Orderly shutdown. Needed because the Notes runtime swallows Ctrl+C, so without this the
    // only way to stop the process is SIGKILL, which skips every bit of cleanup.
    app.post("/api/shutdown", ctx -> {
      Map<String, Object> body = new LinkedHashMap<>();
      boolean accepted = RunnerLifecycle.requestShutdown();
      body.put("status", accepted ? "shutting down" : "not running under --wait; nothing to do");
      ctx.status(accepted ? 202 : 409);
      ctx.json(body);
      // The response has to be on its way before the server stops accepting connections.
    });
  }

  private static void registerErrorHandling(Javalin app) {
    app.exception(DominoService.NotFoundException.class, (e, ctx) -> {
      ctx.status(404);
      ctx.json(error(e.getMessage()));
    });
    app.exception(IllegalArgumentException.class, (e, ctx) -> {
      ctx.status(400);
      ctx.json(error(e.getMessage()));
    });
    app.exception(Exception.class, (e, ctx) -> {
      // Domino failures are reported rather than swallowed: a stack trace on the console
      // plus a readable message in the response.
      System.err.println("[domino-browser] request failed: " + ctx.path());
      e.printStackTrace(System.err);
      ctx.status(500);
      ctx.json(error(e.getClass().getSimpleName() + ": " + e.getMessage()));
    });
  }

  private static Map<String, Object> error(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message == null ? "unknown error" : message);
    return body;
  }

  private static String requiredDb(Context ctx) {
    return required(ctx, "db");
  }

  private static String required(Context ctx, String parameter) {
    String value = ctx.queryParam(parameter);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("query parameter '" + parameter + "' is required");
    }
    return value;
  }
}
