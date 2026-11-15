# Upstream license and NOTICE texts of the agent jar

`generateThirdPartyNotices` copies these texts into `META-INF/THIRD-PARTY-NOTICES` of the agent
jar (O-L04, Apache-2.0 §4(d), BSD-3-Clause condition 2). The upstream jars do not carry them: the
Maven Central jars of protobuf-java, Netty, gRPC and PerfMark have no `META-INF/LICENSE` or
`META-INF/NOTICE`. ByteBuddy's NOTICE and its embedded ASM license come from the ByteBuddy jar and
are not stored here.

The files are verbatim copies taken once, at development time, from the release tag of the
bundled version. The build and the agent never download anything. Do not edit or reformat them
(the SHA-256 below is that of the upstream file).

| File | Upstream file (tag) | Applies to | SHA-256 |
|---|---|---|---|
| `protobuf-java-LICENSE.txt` | <https://github.com/protocolbuffers/protobuf/blob/v36.2/LICENSE> (`v36.2` = protobuf-java 4.36.2, `version.json`) | `com.google.protobuf:protobuf-java` (BSD-3-Clause license text) | `6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d` |
| `netty-NOTICE.txt` | <https://github.com/netty/netty/blob/netty-4.1.139.Final/NOTICE.txt> (`netty-4.1.139.Final`) | every `io.netty` module | `64e368e79be71463922ebb102b33088667cf975edeed8df7f68efe9f89835ca1` |
| `netty-LICENSE.slf4j.txt` | <https://github.com/netty/netty/blob/netty-4.1.139.Final/license/LICENSE.slf4j.txt> (`netty-4.1.139.Final`) | the SLF4J-derived classes of `netty-common` (`io.netty.util.internal.logging`, MIT), shipped with the Netty NOTICE | `e45deaad2824e38410545e2ed8ced91eccd13bc03b41643a4bd79c96e106e106` |
| `grpc-java-NOTICE.txt` | <https://github.com/grpc/grpc-java/blob/v1.85.1/NOTICE.txt> (`v1.85.1`) | every `io.grpc` module | `d891421cec918666dce9dc4516dad6a76557d12203fec121662efff365e72d63` |
| `perfmark-NOTICE.txt` | <https://github.com/perfmark/perfmark/blob/v0.27.0/NOTICE> (`v0.27.0`) | `io.perfmark:perfmark-api` | `7fd15f7217ae4bb4f9b7000b709dbe05f90caf2387c707bfe3a1840e0fc11a4c` |

Why these and no others (checked for the 2026-11 dependency set of the agent jar):

- Of the bundled modules only protobuf-java has a license other than Apache-2.0 (BSD-3-Clause).
  Its binary redistribution needs the copyright line, the conditions and the disclaimer.
- Netty, gRPC and PerfMark have a NOTICE file in their repositories. Guava, Gson, JCTools and
  proto-google-common-protos (googleapis/sdk-platform-java) have none.
- In the source jars of the classes that remain after `minimize()`, the only third-party code under
  a license other than Apache-2.0 or public domain is the SLF4J-derived logging code of Netty
  (`Copyright (c) 2004-2011 QOS.ch`, MIT). The other third-party code listed in Netty's NOTICE
  (Webbit, jbzip2, libdivsufsort, jfastlz, protobuf codec, hpack ports) is not in the jar.

When a version of one of these modules changes, `generateThirdPartyNotices` fails until the text
here is replaced from the new tag and its version in `agent/agent-dist/build.gradle.kts`
(`upstreamTextVersions`) is updated. `checkThirdPartyNotices` (part of `verifyAgentJar`) fails
when a bundled module with a license other than Apache-2.0 lacks a copyright line and its license
text in the jar's THIRD-PARTY-NOTICES, or when a module of `io.netty`, `io.grpc` or `io.perfmark`
lacks its NOTICE. A new dependency with such a license needs its text added here in the same way.
