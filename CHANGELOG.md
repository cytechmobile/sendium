# Changelog

## [0.6.0](https://github.com/cytechmobile/sendium/compare/v0.5.1...v0.6.0) (2026-10-23)


### Features

* **performance:** add asynchronous SMPP provider simulator ([2b9b515](https://github.com/cytechmobile/sendium/commit/2b9b515c2f5b6e7143a54790f16ae8f46e7607ad))
* **performance:** add bounded accounting and latency collection ([1ca3c67](https://github.com/cytechmobile/sendium/commit/1ca3c67f5c33885023fbf2cb16fcee406eafa13e))
* **performance:** add HTTP submission and receipt adapters ([f6dd062](https://github.com/cytechmobile/sendium/commit/f6dd062540642a6902824af97dfe045a7a44f7fd))
* **performance:** add reproducible local load validation ([bc1cde7](https://github.com/cytechmobile/sendium/commit/bc1cde7719e0d9a93707a4e2a282e8293eadb92b))
* **performance:** add run-owned private benchmark networking ([20d7e8d](https://github.com/cytechmobile/sendium/commit/20d7e8d61ebefa28acb524813d26848fa3d53a9d))
* **performance:** add SMPP submission and receipt adapter ([d8e6c18](https://github.com/cytechmobile/sendium/commit/d8e6c1809685dd1de47f0b4d0a9aa527d2f7a0d2))
* **performance:** bootstrap AWS OIDC and control-plane roles ([11e6094](https://github.com/cytechmobile/sendium/commit/11e6094d3595644dd083e4ae393151a0b5894c31))
* **performance:** build native applications with pinned Mandrel ([b9c47d2](https://github.com/cytechmobile/sendium/commit/b9c47d29e17d8c099cb1237ed5f70200ba244938))
* **performance:** clean up owned benchmark stacks and artifacts ([76412e1](https://github.com/cytechmobile/sendium/commit/76412e13979607068dc75056dbc2a0e3fe6e38af))
* **performance:** collect resource evidence and classify validity ([71e102c](https://github.com/cytechmobile/sendium/commit/71e102c91e911435730d2be91d7c89dc1d04603a))
* **performance:** define harness configuration and result contracts ([a3e43df](https://github.com/cytechmobile/sendium/commit/a3e43df68bca9e2a4a9a31fb4540f018edc5f219))
* **performance:** define native application artifact contracts ([99aad7a](https://github.com/cytechmobile/sendium/commit/99aad7afd00fae29659a7c4d2ef7b99fdb394105))
* **performance:** define small Graviton AWS benchmark preset ([473e39f](https://github.com/cytechmobile/sendium/commit/473e39fe4f45b850cfcb6f5eab5d2d7cf612f04c))
* **performance:** deploy and report native application runs ([8eb2c61](https://github.com/cytechmobile/sendium/commit/8eb2c61f0998b5097e2c71bcb45c9c62edcc58f3))
* **performance:** deploy and supervise private benchmark runs ([ea205f6](https://github.com/cytechmobile/sendium/commit/ea205f666a3653852b5cb736933ecbfd048fb8f8))
* **performance:** execute protected AWS benchmark deployments ([3b77762](https://github.com/cytechmobile/sendium/commit/3b777621ae4b907136f3aefffbadfcc3d9046486))
* **performance:** expire abandoned benchmark runs independently ([79f6a3f](https://github.com/cytechmobile/sendium/commit/79f6a3fa28667c2136f6c5a4fd7e2bee3b7b0342))
* **performance:** generate isolated benchmark configuration ([e8795f8](https://github.com/cytechmobile/sendium/commit/e8795f8dcd9c4952ddd73e9329a63082bf7ca2a8))
* **performance:** orchestrate runnable benchmark phases and reports ([e7938b8](https://github.com/cytechmobile/sendium/commit/e7938b8e8f5748d1559bba1c7cd0a0e5cf4e8ee3))
* **performance:** package JVM harness and phase lifecycle ([a343808](https://github.com/cytechmobile/sendium/commit/a3438081ae033f4d07a3baee142cebd9925e583c))
* **performance:** prepare manual exact-revision benchmark builds ([d2c9746](https://github.com/cytechmobile/sendium/commit/d2c974637216abb6e686b45419b7c08b39877f6c))
* **performance:** provision isolated benchmark EC2 and PostgreSQL ([29e1111](https://github.com/cytechmobile/sendium/commit/29e111180ad3f5ff58843f0192737897b7d7b9e0))
* **performance:** publish finalized reports and clean up workflow runs ([9a9879c](https://github.com/cytechmobile/sendium/commit/9a9879c467a3ca4926b17e8ceabb6bbc66facd3d))
* **performance:** report overall and minute measurements ([9ddda67](https://github.com/cytechmobile/sendium/commit/9ddda67a8875201baf47602b4ce81034dceaa012))
* **performance:** schedule fixed-rate bounded traffic ([8cb6d94](https://github.com/cytechmobile/sendium/commit/8cb6d94de07e4d86e6e03a801d880f72dca3e908))


### Bug Fixes

* **ci:** preserve backend failure diagnostics ([4d7115f](https://github.com/cytechmobile/sendium/commit/4d7115f87c81e3c6e4261c1f7f3291fec89d5cee))
* **dlr:** retain provider correlations for seven days ([7bb60b6](https://github.com/cytechmobile/sendium/commit/7bb60b658cbb93f0f25f4ab440aacd696f917d6a))
* **performance:** authorize owned network dependencies ([ac105f6](https://github.com/cytechmobile/sendium/commit/ac105f6760489ceb7238154b65b72010260cd506))
* **performance:** clarify interval rates and outstanding work ([38f514c](https://github.com/cytechmobile/sendium/commit/38f514cdbeaa3af315fb5b0b05e72e75320463c4))
* **performance:** correct outstanding work table formatting ([045238c](https://github.com/cytechmobile/sendium/commit/045238ceb3c5bf6f3667ba3979ce07a264d3c946))
* **performance:** resolve endpoints and automate recovery ([19777d5](https://github.com/cytechmobile/sendium/commit/19777d53e66cabad31f06aa8935af883a4c07630))


### Documentation

* **performance:** add overview and address review comments ([230f78b](https://github.com/cytechmobile/sendium/commit/230f78bdcdcaaf913e3192f9cfa3bc12f310ccd4))
* **performance:** explain workflow parameters ([8a1999b](https://github.com/cytechmobile/sendium/commit/8a1999b8396ac0f691313aa85c665386bcb34a75))
* **performance:** keep implementation planning outside repository ([ef72253](https://github.com/cytechmobile/sendium/commit/ef72253d1c2f627f95cf9ebea794349e4f121fb1))
* **performance:** record AWS bootstrap installation ([f403cb6](https://github.com/cytechmobile/sendium/commit/f403cb6ad3ad630a38934f019ef2caf78a43c1e2))

## [0.5.1](https://github.com/cytechmobile/sendium/compare/v0.5.0...v0.5.1) (2026-09-16)


### Bug Fixes

* **converter:** deploy under dedicated path ([51b1ce9](https://github.com/cytechmobile/sendium/commit/51b1ce9ea15024d745a3969b4854641bd772685d))

## [0.5.0](https://github.com/cytechmobile/sendium/compare/v0.4.2...v0.5.0) (2026-09-15)


### Features

* **core:** make DLR persistence optional ([fdfc485](https://github.com/cytechmobile/sendium/commit/fdfc485d6efee7be534af8e6f418c9a3468683b6))
* **deploy:** add PostgreSQL quick start ([e9a67b2](https://github.com/cytechmobile/sendium/commit/e9a67b2ca596ec6a2d3ffa2523d767d627320673))
* **dlr:** add PostgreSQL message state storage ([289ddde](https://github.com/cytechmobile/sendium/commit/289ddde23a68396fce3a466ac2d343428d90d885))
* **dlr:** add PostgreSQL schema migration ([c6fc49d](https://github.com/cytechmobile/sendium/commit/c6fc49d29ddc25c6356396eab7c4fb61ce544921))
* **dlr:** make downstream delivery durable ([f015294](https://github.com/cytechmobile/sendium/commit/f015294dbbf3936a9fea5fd8d8ef3425e226935a))
* **dlr:** make PostgreSQL the default backend ([29035f2](https://github.com/cytechmobile/sendium/commit/29035f2da5e722e1fa2e50f5fd820f5cc4388161))
* **dlr:** persist unpushed receipts in PostgreSQL ([8cf10d6](https://github.com/cytechmobile/sendium/commit/8cf10d6f4f27b5108ad47f6b33a27c07bb92074c))
* **dlr:** wire configurable storage backend ([54c6161](https://github.com/cytechmobile/sendium/commit/54c61616f5582d29d6fc78e0d28249464db59b12))


### Bug Fixes

* **dlr:** harden PostgreSQL persistence ([65f562c](https://github.com/cytechmobile/sendium/commit/65f562ce08ba3792dffd3f26f45c8167604ea145))
* **dlr:** lease HTTP callback attempts ([047e7ee](https://github.com/cytechmobile/sendium/commit/047e7ee4087a9d66080b2a2dc75d677eda69b367))
* **dlr:** lease SMPP delivery attempts ([153beee](https://github.com/cytechmobile/sendium/commit/153beeea13f604a5c86e5cd8d422541553acac4d))
* **dlr:** reject HTTP redirect responses ([3c72859](https://github.com/cytechmobile/sendium/commit/3c728593bf7465a18ffd44954882f57c0b17188c))
* **dlr:** retry HTTP callbacks hourly ([44ebb93](https://github.com/cytechmobile/sendium/commit/44ebb93bd3ff51f5bb7c48b0db11333482fc3cdb))
* **http:** persist DLR state before queueing ([49aa113](https://github.com/cytechmobile/sendium/commit/49aa1136fc37967a6f773271cbba9049f07eaaa1))
* **smpp:** acknowledge before persistence ([c2fb549](https://github.com/cytechmobile/sendium/commit/c2fb549eb958b7d9df40f95e5e7d8aacfd96da2f))
* **smpp:** persist submissions before acknowledgement ([2485f8b](https://github.com/cytechmobile/sendium/commit/2485f8bc0267f736e344919b517568957191a70c))
* **test:** await container removal before restart ([bd0a618](https://github.com/cytechmobile/sendium/commit/bd0a6183a3cd263a68fc761fa751979fc533a75c))
* **test:** harden native metric polling ([3983018](https://github.com/cytechmobile/sendium/commit/3983018f7d5cd8d5c74b465dfd38131ba4ca6db9))


### Performance Improvements

* **dlr:** parallelize HTTP callback delivery ([26edee7](https://github.com/cytechmobile/sendium/commit/26edee78e44a7b923ca71e5bcacadf3fac28d426))


### Documentation

* **dlr:** detail provider correlation flow ([3e8f9a1](https://github.com/cytechmobile/sendium/commit/3e8f9a10c62469e717b69572f890f0daed06dc36))
* **dlr:** document PostgreSQL operations ([3417fe9](https://github.com/cytechmobile/sendium/commit/3417fe9cbe2e4f5bee5769acee8733847c9fe1f5))

## [0.4.2](https://github.com/cytechmobile/sendium/compare/v0.4.1...v0.4.2) (2026-09-03)


### Documentation

* add quickstart resource requirements ([b3fe37c](https://github.com/cytechmobile/sendium/commit/b3fe37ce60686b179d547a17554dc33c37858f81))
* add repository agent workflow ([fa9bc2e](https://github.com/cytechmobile/sendium/commit/fa9bc2e54cfcc3bf30236dc6cd511de32ed0b5ac))

## [0.4.1](https://github.com/cytechmobile/sendium/compare/v0.4.0...v0.4.1) (2026-08-12)


### Bug Fixes

* **checkstyle:** remove obsolete JavadocStyle check ([f67d4ec](https://github.com/cytechmobile/sendium/commit/f67d4ecb6f9dd2636136a3d444a898e3d2641432))

## [0.4.0](https://github.com/cytechmobile/sendium/compare/v0.3.2...v0.4.0) (2026-08-12)


### Features

* add secure quick-start installer ([5a899f3](https://github.com/cytechmobile/sendium/commit/5a899f3df7d4ed59f649ce3cd1b72aaf97cb31b5))

## [0.3.2](https://github.com/cytechmobile/sendium/compare/v0.3.1...v0.3.2) (2026-07-09)


### Bug Fixes

* **core:** cover StandardMessage byte JSON handling ([f4cf8d6](https://github.com/cytechmobile/sendium/commit/f4cf8d65fbb98de8c5dd0ec53770201b9deff0e1))

## [0.3.1](https://github.com/cytechmobile/sendium/compare/v0.3.0...v0.3.1) (2026-07-02)


### Bug Fixes

* **deps:** release Quarkus platform upgrade ([085863b](https://github.com/cytechmobile/sendium/commit/085863bb3330be7b78da2488b1cc80072b588227))

## [0.3.0](https://github.com/cytechmobile/sendium/compare/v0.2.7...v0.3.0) (2026-06-15)


### Features

* **conf:** add browser-based Kannel migration converter ([b4c36dc](https://github.com/cytechmobile/sendium/commit/b4c36dcd1b4d311c3f7cdef14f572f032ced542e))
* **converter:** Added migrator handling on routing rules ([b5c8cc8](https://github.com/cytechmobile/sendium/commit/b5c8cc8ad7da2f13961b96aaf932ccebb3f4539c))

## [0.2.7](https://github.com/cytechmobile/sendium/compare/v0.2.6...v0.2.7) (2026-06-09)


### Bug Fixes

* **smpp:** avoid leaking pending session contexts on rejected binds ([d6c83c1](https://github.com/cytechmobile/sendium/commit/d6c83c1150a1bc9e486ccddf248d5a6422ef81c2))

## [0.2.6](https://github.com/cytechmobile/sendium/compare/v0.2.5...v0.2.6) (2026-06-03)


### Bug Fixes

* **ci:** merge dependabot PR by explicit number ([ba1793c](https://github.com/cytechmobile/sendium/commit/ba1793c86f47457eb9309b84f547f19fb54a54ba))

## [0.2.5](https://github.com/cytechmobile/sendium/compare/v0.2.4...v0.2.5) (2026-06-02)


### Reverts

* **ci:** revert auto-merge-release-snapshot ([cf29ba8](https://github.com/cytechmobile/sendium/commit/cf29ba885f3922b4bbf92b4eea0a67f9f3b8d27e))

## [0.2.4](https://github.com/cytechmobile/sendium/compare/v0.2.3...v0.2.4) (2026-06-02)


### Bug Fixes

* **DLR:** Avoid creating the MVStore when the service is unused ([a57fa55](https://github.com/cytechmobile/sendium/commit/a57fa55afcb76fa42d410cfede20b20c2c5cc480))

## [0.2.3](https://github.com/cytechmobile/sendium/compare/v0.2.2...v0.2.3) (2026-06-01)


### Bug Fixes

* **release:** keep sendium-core dependencyManagement version in lockstep on release ([2dd8eba](https://github.com/cytechmobile/sendium/commit/2dd8eba765afbf9a9667c1152e5d9c06faf1c365))

## [0.2.2](https://github.com/cytechmobile/sendium/compare/v0.2.1...v0.2.2) (2026-06-01)


### Documentation

* Polish public docs and metadata ([5651af4](https://github.com/cytechmobile/sendium/commit/5651af427b31c1ee99aec325290581b016a702a0))

## [0.2.1](https://github.com/cytechmobile/sendium/compare/v0.2.0...v0.2.1) (2026-05-29)


### Bug Fixes

* **smpp:** route UDH multipart SMS ([445beb3](https://github.com/cytechmobile/sendium/commit/445beb3bd45c37a698b2dbde435f982a294a0882))


### Documentation

* **features:** Added a clear Current Features section ([7589134](https://github.com/cytechmobile/sendium/commit/75891349a8f61b06165202fa23a861a2efc090da))

## [0.2.0](https://github.com/cytechmobile/sendium/compare/v0.1.0...v0.2.0) (2026-05-25)


### Features

* **DLR:** Persist and replay unpushed SMPP DLRs ([69658d2](https://github.com/cytechmobile/sendium/commit/69658d2e77dfedc5e45bde01fa92b39b6c58bae2))


### Bug Fixes

* **release:** remove Maven self dependency cycle ([02e8282](https://github.com/cytechmobile/sendium/commit/02e828290acf292995e5a2fa51967c211106265b))

## Changelog

All notable changes to this project will be documented in this file.

This changelog is maintained by [Release Please](https://github.com/googleapis/release-please) from Conventional Commit messages.
