# Upstream license and NOTICE texts of the server jar

`generateThirdPartyNotices` (`server/build.gradle.kts`, `registerBundledNotices`) writes
`META-INF/THIRD-PARTY-NOTICES` of the server boot jar and the `/opt/argos/THIRD-PARTY-NOTICES` file
of the server image (O-L04, Apache-2.0 §4(d), BSD and MIT redistribution conditions). Most texts
come from the bundled jars themselves:

- every `META-INF/NOTICE*` of a bundled jar (Spring, Tomcat, Log4j, Jackson, Micrometer, ...);
- the `META-INF/LICENSE*` of a bundled jar whose license is not Apache-2.0 (PostgreSQL JDBC,
  SLF4J, Checker Framework qualifiers, HdrHistogram, Jakarta Activation and XML Binding APIs),
  and the `META-INF/licenses/**` it embeds (the SCRAM and stringprep libraries inside the
  PostgreSQL driver).

The files here are the texts that the bundled jars do not carry. They are verbatim copies taken
once, at development time, from the release tag of the bundled version; the build and the server
never download anything. Do not edit or reformat them (the SHA-256 is that of the upstream file).

| File | Upstream file (tag) | Applies to | SHA-256 |
|---|---|---|---|
| `protobuf-java-LICENSE.txt` | <https://github.com/protocolbuffers/protobuf/blob/v36.2/LICENSE> (`v36.2` = protobuf-java 4.36.2) | `com.google.protobuf:protobuf-java` (BSD-3-Clause) | `6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d` |
| `asm-LICENSE.txt` | <https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_7/LICENSE.txt> (`ASM_9_7`) | `org.ow2.asm:asm` (BSD-3-Clause) | `293b6af371eee28b0ff16f0334ea19e20a3d5522143faa4b95b346855507879a` |
| `reactive-streams-LICENSE.txt` | <https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.4/LICENSE> (`v1.0.4`) | `org.reactivestreams:reactive-streams` (MIT-0) | `96a3b2d45af72054d0676e8eb9a944a096812a7f8ff1b71bd82dadbc60ce0444` |
| `zstd-jni-LICENSE.txt` | <https://github.com/luben/zstd-jni/blob/v1.5.7-23/LICENSE> (`v1.5.7-23`) | `com.github.luben:zstd-jni` (BSD-2-Clause) | `289a6adada5726047b9e68bf607cc44fbbac953bbb4fd29f53ffe75b5cb3e7b1` |
| `zstd-LICENSE.txt` | <https://github.com/facebook/zstd/blob/v1.5.7/LICENSE> (`v1.5.7`) | the Zstandard library compiled into the native code of `zstd-jni` 1.5.7-23 (BSD-3-Clause choice of its dual license), shipped with the zstd-jni text | `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8` |
| `netty-NOTICE.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/NOTICE.txt> (`netty-4.2.19.Final`) | every `io.netty` module | `1775b0d91666fcb82493a5198f761b59a6d4d050bae6a4d26ecc377354068571` |
| `netty-LICENSE.slf4j.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.slf4j.txt> | SLF4J-derived logging classes of `netty-common` (MIT) | `e45deaad2824e38410545e2ed8ced91eccd13bc03b41643a4bd79c96e106e106` |
| `netty-LICENSE.webbit.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.webbit.txt> | Webbit-derived WebSocket code of `netty-codec-http` (BSD-3-Clause) | `9a746c7a218e6ee1ef719a39a0a66454c82ada93c60f06f9f819b505f6401046` |
| `netty-LICENSE.jbzip2.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.jbzip2.txt> | jbzip2-derived code of `netty-codec-compression` (MIT) | `77edfb9953d4a58f573c40280f9703df301b1f28e68b556914a561b03559c5e9` |
| `netty-LICENSE.libdivsufsort.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.libdivsufsort.txt> | libdivsufsort-derived code of `netty-codec-compression` (MIT) | `a801f489b279d91b8afc67d41e3769ec4df476fda218d63f4326c005db881541` |
| `netty-LICENSE.jfastlz.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.jfastlz.txt> | jfastlz-derived code of `netty-codec-compression` (MIT) | `06c1bf4cd5304c64bb72a6e03b461c389c35af26d623e3add7d828c398482e1d` |
| `netty-LICENSE.hyper-hpack.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.hyper-hpack.txt> | hyper-hpack-derived HPACK code of `netty-codec-http2` (MIT) | `763a9342a04df62046c9dc748a5287934eb0a5331c6863b3ca0aee20e18cb4ed` |
| `netty-LICENSE.nghttp2-hpack.txt` | <https://github.com/netty/netty/blob/netty-4.2.19.Final/license/LICENSE.nghttp2-hpack.txt> | nghttp2-derived HPACK code of `netty-codec-http2` (MIT) | `6b94f3abc1aabd0c72a7c7d92a77f79dda7c8a0cb3df839a97890b4116a2de2a` |
| `grpc-java-NOTICE.txt` | <https://github.com/grpc/grpc-java/blob/v1.85.1/NOTICE.txt> (`v1.85.1`) | every `io.grpc` module | `d891421cec918666dce9dc4516dad6a76557d12203fec121662efff365e72d63` |
| `perfmark-NOTICE.txt` | <https://github.com/perfmark/perfmark/blob/v0.27.0/NOTICE> (`v0.27.0`) | `io.perfmark:perfmark-api` | `7fd15f7217ae4bb4f9b7000b709dbe05f90caf2387c707bfe3a1840e0fc11a4c` |
| `prometheus-client_java-NOTICE.txt` | <https://github.com/prometheus/client_java/blob/v1.7.0/NOTICE> (`v1.7.0`) | every `io.prometheus` module (`prometheus-metrics-*`) | `3ac53f224952affb6160f29912f6be7472219dd2089f7e2474072ed4f39c230c` |

Why these and no others (checked for the 2026-11 dependency set of the server):

- Bundled modules with a license other than Apache-2.0 whose jar has no `META-INF/LICENSE*`:
  protobuf-java, ASM, Reactive Streams and zstd-jni. `animal-sniffer-annotations` is
  `Apache-2.0 OR MIT` and is used under Apache-2.0.
- Apache-2.0 projects with a NOTICE file in their repository whose jars carry none: Netty, gRPC,
  PerfMark and the Prometheus Java client. ClickHouse Java, Guava, Gson, HikariCP, Bucket4j,
  RoaringBitmap, lz4-java, SnakeYAML, Flyway and proto-google-common-protos
  (googleapis/sdk-platform-java) have none at the bundled tags.
- Netty's NOTICE names third-party code it contains. The texts of the components under a license
  other than Apache-2.0 or public domain that the shipped Netty modules contain (SLF4J, Webbit,
  jbzip2, libdivsufsort, jfastlz, hyper-hpack, nghttp2) are appended to the Netty NOTICE.

When a version of one of these modules changes, `generateThirdPartyNotices` fails until the text
here is replaced from the new tag and its version in `server/build.gradle.kts` is updated. A new
dependency with a license other than Apache-2.0 whose jar has no license file fails the same task
until its text is added here. `verifyServerJar` (finalizes `bootJar`, T-BLD-003a) fails when
`THIRD-PARTY-NOTICES` misses a module, a license text or one of these NOTICE texts.
