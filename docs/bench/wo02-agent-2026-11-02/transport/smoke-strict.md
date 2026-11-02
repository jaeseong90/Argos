# P-3 smoke (2026-11-02T19:57:43)

| run | exit | batches | first batch ms | TLS | stdout | stderr | tmp created | agent ERROR | result |
|---|---|---|---|---|---|---|---|---|---|
| base@8 | 0 | 0 | -1 | - | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-lite@8 | 0 | 3 | 1828 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-full@8 | 0 | 3 | 1579 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full@8 | 0 | 3 | 1567 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-lite@8 | 0 | 3 | 1196 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-full@8 | 0 | 3 | 1159 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-lite@8 | 0 | 3 | 1054 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| http1-full@8 | 0 | 3 | 1024 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full-x@8 | 0 | 3 | 1568 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-full-x@8 | 0 | 3 | 1017 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| base@21 | 0 | 0 | -1 | - | 0 | 0 | 0 | 0 | PASS |
| grpc-netty-lite@21 | 0 | 3 | 1333 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | FAIL: temp files (T-BLD-006): left [], created during the run [socket_#] |
| grpc-netty-full@21 | 0 | 3 | 1339 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | FAIL: temp files (T-BLD-006): left [], created during the run [socket_#] |
| grpc-netty41-full@21 | 0 | 3 | 1328 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | FAIL: temp files (T-BLD-006): left [], created during the run [socket_#] |
| grpc-okhttp-lite@21 | 0 | 3 | 1149 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| grpc-okhttp-full@21 | 0 | 3 | 1123 | TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0 | 0 | 0 | 0 | PASS |
| http1-lite@21 | 0 | 3 | 973 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| http1-full@21 | 0 | 3 | 962 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |
| grpc-netty41-full-x@21 | 0 | 3 | 1312 | TLSv1.3 TLS_AES_128_GCM_SHA256 | 0 | 0 | 1 | 0 | FAIL: temp files (T-BLD-006): left [], created during the run [socket_#] |
| http1-full-x@21 | 0 | 3 | 969 | TLSv1.3 TLS_AES_256_GCM_SHA384 | 0 | 0 | 0 | 0 | PASS |

4 run(s) failed:
- grpc-netty-lite@21: temp files (T-BLD-006): left [], created during the run [socket_#]
- grpc-netty-full@21: temp files (T-BLD-006): left [], created during the run [socket_#]
- grpc-netty41-full@21: temp files (T-BLD-006): left [], created during the run [socket_#]
- grpc-netty41-full-x@21: temp files (T-BLD-006): left [], created during the run [socket_#]
