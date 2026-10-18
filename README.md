# ibridge-jdbc

JT400/JDBC backend for iBridge. Runs SQL (JDBC) and CL (CommandCall) on an
IBM i through the IBM i host servers (signon 8476, database 8471, remote
command 8475) using the JTOpen jt400 driver - an alternative to SSH for hosts
whose SSH sign-in is unavailable.

- `GET /health` -> `{"ok":true}`
- `POST /run` with header `x-api-key: <APP_KEY>` and JSON body
  `{"host","username","password","kind":"sql"|"cl","command"}`
  returns `{"ok":true,"output":"..."}` or `{"ok":false,"message":"..."}`.
  Credentials arrive per request and are never stored or logged.

Deploy: Docker (see Dockerfile). Render blueprint in render.yaml.
