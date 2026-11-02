# P-3 smoke (2026-11-02T19:55:59)

| run | exit | batches | first batch ms | TLS | stdout | stderr | tmp created | agent ERROR | result |
|---|---|---|---|---|---|---|---|---|---|
| base@8 | 0 | 0 | -1 | - | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-lite@8 | 0 | 3 | 1989 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-full@8 | 0 | 3 | 1591 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full@8 | 0 | 3 | 1577 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-lite@8 | 0 | 3 | 1187 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-full@8 | 0 | 3 | 1160 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-lite@8 | 0 | 3 | 1062 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| http1-full@8 | 0 | 3 | 1022 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full-x@8 | 0 | 3 | 1562 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-full-x@8 | 0 | 3 | 1025 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| base@21 | 0 | 0 | -1 | - | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-lite@21 | 0 | 3 | 1356 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | KNOWN FINDING: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending) |
| grpc-netty-full@21 | 0 | 3 | 1341 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | KNOWN FINDING: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending) |
| grpc-netty41-full@21 | 0 | 3 | 1332 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | KNOWN FINDING: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending) |
| grpc-okhttp-lite@21 | 0 | 3 | 1122 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-full@21 | 0 | 3 | 1112 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-lite@21 | 0 | 3 | 976 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| http1-full@21 | 0 | 3 | 975 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full-x@21 | 0 | 3 | 1316 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | KNOWN FINDING: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending) |
| http1-full-x@21 | 0 | 3 | 975 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |

No unexpected result (4 known finding(s)).

Known findings (not counted as failures; pass --known-af-unix-temp= empty, Gradle -Ptransport.poc.strict=true, to count them):
- grpc-netty-lite@21: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending)
- grpc-netty-full@21: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending)
- grpc-netty41-full@21: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending)
- grpc-netty41-full-x@21: temp files (T-BLD-006): left [], created during the run [socket_#] = known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted at once; T-BLD-006 decision pending)
