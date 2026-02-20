# AccountManagerService7 — Futures: App Container Migration

## Current State

- **Container:** Apache Tomcat (external WAR deployment)
- **REST Framework:** Jersey 3.1.5 / Jakarta RS 3.1.0
- **Servlet Spec:** Jakarta Servlet 6.1 / Jakarta EE 11
- **WebSocket:** Jakarta WebSocket (Tomcat implementation)
- **Auth:** JAAS (`AM7LoginModule`) + JWT (`TokenFilter`)
- **Connection Pool:** `org.apache.tomcat.jdbc.pool.DataSourceFactory` (PostgreSQL)
- **CORS:** `org.apache.catalina.filters.CorsFilter` (Tomcat-specific)
- **Caching Filter:** `org.apache.catalina.filters.ExpiresFilter` (Tomcat-specific)
- **Session Management:** `org.apache.catalina.session.PersistentManager` + `FileStore`
- **Packaging:** WAR

### Tomcat Coupling Assessment

All Java source uses **standard Jakarta APIs only** (`jakarta.servlet.*`, `jakarta.websocket.*`). Zero imports of `org.apache.catalina` or `org.apache.tomcat` in application code. Tomcat-specific coupling exists only in XML configuration:

| Coupling Point | File | Tomcat-Specific Class/Config |
|---|---|---|
| CORS filter | `web.xml` | `org.apache.catalina.filters.CorsFilter` |
| Expires filter | `web.xml` | `org.apache.catalina.filters.ExpiresFilter` |
| JAAS Realm | `context.xml` | `org.apache.catalina.realm.JAASRealm` |
| Connection pool | `context.xml` | `org.apache.tomcat.jdbc.pool.DataSourceFactory` |
| Session store | `context.xml` | `org.apache.catalina.session.PersistentManager` / `FileStore` |
| WebSocket buffer sizes | `web.xml` | `org.apache.tomcat.websocket.*` context params |

**Migration risk: Low.** No code changes required for any container that supports Jakarta Servlet 6.x.

---

## Constraint: No Frameworks

This project does not use and will not adopt Spring, Spring Boot, Micronaut, Quarkus, or similar frameworks. The REST layer is Jersey (JAX-RS reference implementation) wired directly to the servlet container. Any replacement container must support:

1. Jakarta Servlet 6.x
2. Jakarta WebSocket
3. Jersey 3.x as a servlet
4. JAAS authentication
5. JNDI DataSource (or programmatic DataSource equivalent)
6. Embeddable (executable JAR preferred over external container + WAR)

---

## Recommended Options

### Option 1: Embedded Tomcat (Minimum Risk)

**Approach:** Keep Tomcat but embed it inside the application. Replace WAR deployment with a self-contained executable JAR.

**What stays the same:**
- All Java source — unchanged
- All filters (CorsFilter, ExpiresFilter) — same classes, same config
- JAAS Realm — same configuration
- Connection pool factory — same
- Session management — same

**What changes:**
- Add dependencies: `tomcat-embed-core`, `tomcat-embed-websocket`, `tomcat-embed-jasper` (if needed)
- Write a `Main.java` (~30-50 lines) that programmatically boots Tomcat, registers the WAR context, and starts the server
- Change Maven packaging from `war` to `jar`
- Move `web.xml` and `context.xml` loading to classpath-based or programmatic setup
- Remove external Tomcat installation from deployment

**Effort:** Half a day. Near-zero risk.

**Gains:**
- Self-contained executable JAR (`java -jar AccountManagerService7.jar`)
- No external Tomcat installation or management
- Simpler deployment
- Same runtime behavior

**Limitations:**
- Still Tomcat. If the motivation is Tomcat-specific bugs or performance, this doesn't address them.
- Tomcat's connection pool (`tomcat-jdbc`) is adequate but not best-in-class.

---

### Option 2: Eclipse Jetty Embedded (Best Overall)

**Approach:** Replace Tomcat with Jetty as an embedded server. Jetty is the most natural container for Jersey — Jersey's own test infrastructure runs on Jetty.

**What stays the same:**
- All REST service classes (`@Path`, `@GET`, `@POST`, etc.) — unchanged
- All servlets (MediaServlet, ThumbnailServlet, etc.) — unchanged (standard `HttpServlet`)
- WebSocket endpoints — unchanged (standard `@ServerEndpoint`)
- JAAS LoginModule (`AM7LoginModule`) — unchanged
- TokenFilter — unchanged (standard `jakarta.servlet.Filter`)
- All business logic (Objects7, Agent7) — unchanged

**What changes:**
- Replace Tomcat dependencies with Jetty: `jetty-server`, `jetty-servlet`, `jetty-websocket-jakarta-server`, `jetty-jndi`
- Replace `org.apache.catalina.filters.CorsFilter` with a standard Jakarta `Filter` implementation (write once, ~40 lines, no Tomcat dependency)
- Replace `org.apache.catalina.filters.ExpiresFilter` with Jetty's `HeaderFilter` or a standard Jakarta filter
- Replace `org.apache.tomcat.jdbc.pool.DataSourceFactory` with HikariCP (superior pool, widely considered the best Java connection pool)
- Replace `context.xml` JAAS Realm with Jetty's `JAASLoginService` configuration
- Replace `context.xml` session config with Jetty's session management
- Write a `Main.java` that programmatically configures and starts Jetty
- Change Maven packaging from `war` to `jar`

**Effort:** 2-4 days.

**Gains:**
- Self-contained executable JAR
- Lower memory footprint than Tomcat
- First-class Jersey integration (Jersey's own CI runs on Jetty)
- HikariCP connection pool (faster, more reliable than Tomcat pool)
- HTTP/2 support built in
- Active Eclipse Foundation maintenance
- Programmatic configuration replaces scattered XML files
- Better embeddability story for testing (spin up a real server in unit tests)

**Risks:**
- JAAS configuration differs — must verify `AM7LoginModule` integration works under Jetty's `JAASLoginService`
- WebSocket buffer size tuning uses different configuration surface

---

### Option 3: Undertow Embedded (Best Performance)

**Approach:** Replace Tomcat with Red Hat's Undertow. Highest-throughput Java web server, NIO-based. Powers WildFly internally.

**What stays the same:**
- All REST service classes — unchanged
- All servlets — unchanged (via `undertow-servlet` bridge)
- WebSocket endpoints — unchanged (via `undertow-websocket-jsr` bridge)
- JAAS LoginModule — unchanged
- TokenFilter — unchanged
- All business logic — unchanged

**What changes:**
- Replace Tomcat dependencies with Undertow: `undertow-core`, `undertow-servlet`, `undertow-websocket-jsr`
- CORS and Expires filters replaced with standard Jakarta filters or Undertow handlers
- Connection pool replaced with HikariCP (programmatic DataSource, no JNDI XML)
- JAAS wiring is manual — no declarative Realm XML. Must programmatically configure the identity manager to delegate to JAAS
- Write a `Main.java` with Undertow's `DeploymentInfo` API to register servlets, filters, and WebSocket endpoints
- Change Maven packaging from `war` to `jar`

**Effort:** 1-2 weeks.

**Gains:**
- Fastest raw throughput of any Java servlet container
- Lowest memory footprint
- Excellent WebSocket and streaming performance — benefits the Olio simulation streaming and chat WebSocket workloads
- NIO-based architecture handles many concurrent connections efficiently
- Self-contained executable JAR

**Risks:**
- Most manual setup of the three options
- JNDI support is minimal — effectively requires moving to programmatic DataSource management
- JAAS integration requires more custom wiring than Jetty or Tomcat
- Smaller community than Tomcat/Jetty (though well-maintained by Red Hat)

---

## Not Recommended

| Option | Reason |
|---|---|
| **Javalin** | Own routing model, incompatible with JAX-RS. Would require rewriting all `@Path` endpoints. |
| **Helidon SE** | Own routing model, not JAX-RS compatible. Helidon MP supports JAX-RS but is a MicroProfile framework. |
| **Eclipse GlassFish** | Full Jakarta EE server. Heavier than Tomcat, not lighter. |
| **WildFly** | Full application server. Overkill. Uses Undertow internally — use Undertow directly. |
| **Grizzly** | Was Jersey's reference container but effectively in maintenance mode since Oracle stepped back. Not a safe long-term choice. |

---

## Shared Migration Benefits (All Options)

Regardless of which container is chosen, all three options enable:

1. **Executable JAR deployment** — `java -jar service.jar` replaces WAR-to-Tomcat deployment
2. **Programmatic configuration** — Java code replaces `web.xml` and `context.xml` XML, easier to version control and reason about
3. **HikariCP connection pool** — upgrade from Tomcat's pool to the industry standard (Options 2 and 3; Option 1 can also adopt this)
4. **Standard Jakarta CORS filter** — removes the one area of Tomcat-specific code, making the app fully container-agnostic
5. **Simpler CI/CD** — no container installation step, just build the JAR and run it
6. **Test server embedding** — spin up a real server instance inside integration tests

---

## Recommended Path

1. **Immediate (low effort):** Embedded Tomcat. De-risks the move away from external Tomcat with near-zero code changes. Validates that the app runs as an executable JAR.
2. **Next (moderate effort):** Migrate from Embedded Tomcat to Jetty Embedded. Replace the connection pool with HikariCP. Replace Tomcat-specific filters with standard Jakarta filters. This is the long-term target.
3. **Optional (if needed):** If WebSocket/streaming benchmarks show a bottleneck, evaluate Undertow as a Jetty replacement for the performance-critical path.

This staged approach lets each step be validated independently without risking a large-bang migration.
