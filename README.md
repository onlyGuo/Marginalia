<p align="center">
  <a href="./README_CN.md">中文</a> | <b>English</b>
</p>

<p align="center">
  <img src="https://img.shields.io/github/license/onlyGuo/Marginalia?style=flat-square&color=green" alt="License" />
  <img src="https://img.shields.io/github/last-commit/onlyGuo/Marginalia?style=flat-square&logo=github&color=purple" alt="Last Commit" />
  <br/>
  <img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.x" />
  <img src="https://img.shields.io/badge/Maven%20Central-1.0.0-blue?style=flat-square&logo=apachemaven&logoColor=white" alt="Maven Central" />
</p>

<h1 align="center">Marginalia</h1>

<p align="center">
  <b>Generate API Documentation from Source Code Comments for Spring Boot</b>
</p>

<p align="center">
  Extract API docs directly from Javadoc and source comments — no annotation invasion required.<br/>
  Includes a compact IDEA-inspired Web UI for browsing APIs, exploring entities, and debugging requests.
</p>

<p align="center">
  <a href="https://github.com/onlyGuo/Marginalia/stargazers"><img src="https://img.shields.io/github/stars/onlyGuo/Marginalia?style=social" alt="GitHub Stars" /></a>
  &nbsp;
  <a href="https://github.com/onlyGuo/Marginalia/network/members"><img src="https://img.shields.io/github/forks/onlyGuo/Marginalia?style=social" alt="GitHub Forks" /></a>
  &nbsp;
  <a href="https://github.com/onlyGuo/Marginalia/watchers"><img src="https://img.shields.io/github/watchers/onlyGuo/Marginalia?style=social" alt="GitHub Watchers" /></a>
</p>

---

<p align="center">
  <img src="doc/1.png" alt="Marginalia controller and endpoint overview" />
  <br/>
  <sub>Controller and endpoint overview</sub>
</p>

---

## Features

- **Zero Annotation Invasion** — No extra annotations needed; extracts docs from Javadoc, line comments, and Swagger annotations
- **Priority Chain** — Javadoc > Line Comments > Swagger Annotations > Method Signature
- **Built-in Web UI** — Compact IDEA-inspired interface with Document and Debug modes
- **API Debugger** — Built-in HTTP client for sending requests, custom headers/params, response viewing, and SSE support
- **Entity Auto-Discovery** — Automatically discovers entity classes from request/response body types with full field structure
- **Field-Level Merge** — User edits are preserved across re-scans
- **Directory Persistence** — Documentation stored as files; all edits survive restarts
- **Out of the Box** — Spring Boot Starter; just add the dependency

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>ink.icoding</groupId>
    <artifactId>marginalia-spring-boot-starter</artifactId>
    <version>1.1.5</version><!-- version -->
</dependency>
```

### 2. Start Application

Start your Spring Boot application, then visit `http://localhost:8080/marginalia` to view the documentation.

### 3. Configure (Optional)

Add to `application.yaml`:

```yaml
marginalia:
  enabled: true                    # Enable/disable, default: true
  prefix: /marginalia             # Web UI path, default: /marginalia
  base-package: com.example       # Base package to scan, empty = scan all
  source-dirs:                    # Source directories, empty = auto-detect
    - src/main/java
  data-dir: ./marginalia-data     # Documentation data directory
  auto-scan: true                 # Auto-scan on startup
  debugger-enabled: true          # Enable debugger feature
  title: API Documentation        # Custom title
```

## How It Works

1. **Startup Scan** — Scans all Java files in the project's source directories at application startup
2. **Parse Controllers** — Uses JavaParser to parse `@Controller` / `@RestController` classes
3. **Extract Docs** — Extracts API descriptions from Javadoc, comments, and annotations by priority
4. **Discover Entities** — Automatically discovers and parses entity classes from request/response types (supports nested generics)
5. **Merge & Persist** — Merges with existing data; user-modified fields take priority
6. **Serve UI** — Displays documentation through a built-in web interface with browse and debug capabilities

## Documentation Source Priority

| Source | Example | Priority |
|--------|---------|----------|
| Javadoc | `/** Get user info */` | Highest |
| Line Comment | `// Get user info` | High |
| Swagger Annotation | `@ApiOperation("Get user info")` | Medium |
| Method Signature | `getUserInfo()` | Lowest |

## Web UI

### Document Mode
- API path, HTTP method, and description
- Path variables, query parameters, header parameters
- Request body (with entity field-level display)
- Response body (with entity field-level display + JSON example)
- Clickable entity names that navigate to entity detail pages

<p align="center">
  <img src="doc/2.png" alt="Nested entity and enum documentation" />
  <br/>
  <sub>Nested entities and enums are expanded recursively in request bodies</sub>
</p>

### Quick Navigation

Press `Ctrl/Cmd + K` to search endpoint names, paths, controllers, entities, and enums from one place, then jump directly to the selected documentation.

<p align="center">
  <img src="doc/3.png" alt="Quick navigation across endpoints and entities" />
  <br/>
  <sub>Quick navigation across endpoints and entities</sub>
</p>

### Entities & Enums

- Dedicated entity navigator with name and package search
- Recursive rendering for nested models, collection generics, and enum values
- Reverse references showing which endpoints use each entity

<p align="center">
  <img src="doc/4.png" alt="Entity and enum details" />
  <br/>
  <sub>Entity fields, enum values, and endpoint references</sub>
</p>

### Debug Mode
- Select HTTP method and enter URL
- Custom Params, Headers, and Body
- Send requests and view responses
- SSE (Server-Sent Events) support
- Edits auto-saved; persist across page refreshes

<p align="center">
  <img src="doc/5.png" alt="API debugging workspace in Debug mode" />
  <br/>
  <sub>Edit request parameters and inspect live responses in Debug mode</sub>
</p>

## Project Structure

```
marginalia/
├── marginalia-core                    # Core library
│   ├── model/                         # Data models
│   ├── parser/                        # Source parser (JavaParser)
│   ├── scanner/                       # Source scanner
│   ├── persistence/                   # Directory-based persistence
│   └── service/                       # Core service
└── marginalia-spring-boot-starter     # Spring Boot Starter
    ├── autoconfigure/                 # Auto-configuration
    └── resources/static/marginalia/   # Web UI (single-file SPA)
```

## License

[GPLv3](./LICENSE)
