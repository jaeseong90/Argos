# proto

The `argos.v1` protobuf messages and gRPC services shared by agent and server, and `PROTOCOL_VERSION` (single integer, DSN-19-10).

- Gradle project: `:proto`
- Java package: `io.argos.proto`
- Design: [11 protocol design](../docs/11-protocol-design.md), [19 §3.4](../docs/19-repo-build-release-design.md)

Protos go to `src/main/proto/argos/v1/` (WO-11). Generated code is a build output and is never committed. Java 8 target so that test harnesses and the agent can use it.
