# Open-source resources and license record

This file records direct dependencies/assets and the alternatives considered for the MVP. It is an engineering inventory, not legal advice. Before public distribution, regenerate a dependency report from the lockfile and include all transitive notices required by the selected licenses.

## Adopted frontend/runtime resources

| Resource | Use | License | Source and decision |
| --- | --- | --- | --- |
| Vue 3 | UI runtime | MIT | [vuejs/core](https://github.com/vuejs/core) and its [license](https://github.com/vuejs/core/blob/main/LICENSE). Adopted through uni-app. |
| uni-app Vue 3 packages | WeChat mini-program compilation/runtime | Apache-2.0 for the open-source packages; DCloud also publishes a product-use agreement | [dcloudio/uni-app](https://github.com/dcloudio/uni-app), [DCloud license explanation](https://dcloud.io/license/uni-app.html). Adopted because WeChat is the primary target. Review the exact DCloud runtime terms if the project later targets native App packaging. |
| `three-platformize` | Three.js adaptation, GLTF loading, orbit controls, raycast and screenshot support in WeChat mini-program | npm package declares MIT | [deepkolos/three-platformize](https://github.com/deepkolos/three-platformize). Adopted and version-pinned for the proven WeChat path. Upstream says maintenance moved to `platformize`; this is therefore a compatibility dependency, not an assumption of active maintenance. |
| Three.js code bundled by the adapter | WebGL scene/rendering primitives | MIT | [mrdoob/three.js](https://github.com/mrdoob/three.js), [MIT license](https://github.com/mrdoob/three.js/blob/dev/LICENSE). Keep the adapter and Three revision pair pinned and tested together. |
| Native mini-program Canvas 2D APIs | Picture annotation | Platform API, not a third-party library copied into the repository | Used for points/circles/freehand and normalized geometry. This keeps the critical editor small and avoids another rendering adapter. |

### 3D hand and derived standard images

| Item | License | Provenance and modifications |
| --- | --- | --- |
| “Hand realistic by Dan Ulrich (CC0).stl” | CC0 1.0 Universal / public-domain dedication | [Wikimedia Commons source page](https://commons.wikimedia.org/wiki/File:Hand_realistic_by_Dan_Ulrich_(CC0).stl). The page attributes Blender Studio, Dan Ulrich, Paul Kotelevets, Tonatiuh de San Julián and Julien Kaspar and links the Blender Human Base Meshes source. |
| `frontend/src/static/hand/hand-lowpoly.glb` | Derived from the CC0 item above | Geometry is reduced for mini-program delivery and converted to GLB. It does not add a new third-party restriction. Keep the source URL and processing notes with future replacements. |
| `hand-palm.png`, `hand-dorsal.png`, `hand-side.png` | Derived from the same CC0 item | Orthographic standard views generated from the converted model. They are system-provided visual sources, not medical anatomy plates. |

CC0 does not require attribution, but retaining provenance is valuable for reproducibility and portfolio review. The hotspot layer, region IDs and annotations are project code/data; they are not taken from the mesh's triangle or vertex IDs.

## Adopted local infrastructure

| Resource | Compose use | License | Notes |
| --- | --- | --- | --- |
| PostgreSQL 16 | Business database image | PostgreSQL License | [PostgreSQL source](https://www.postgresql.org/ftp/source/) and [license](https://www.postgresql.org/about/licence/). |
| pgvector PostgreSQL image/extension | Separate AI PostgreSQL runtime | PostgreSQL License | [pgvector repository](https://github.com/pgvector/pgvector) and [license](https://github.com/pgvector/pgvector/blob/master/LICENSE). Environment is ready, but the future AI migration is not applied. |
| MinIO server | Local S3-compatible object storage | GNU AGPLv3 for the selected server release; commercial licensing is also offered | [MinIO repository](https://github.com/minio/minio), [official container guide](https://min.io/docs/minio/container/index.html), and [compliance statement](https://min.io/compliance). Compose uses the official Quay image path because the Docker Hub pull path is no longer reliable. The upstream OSS repository was archived in April 2026, so this pinned legacy release is for learning/local development only; reassess maintenance, image availability, security updates and licensing before any non-learning deployment. |
| RabbitMQ server | Future image-processing queue | Mozilla Public License 2.0 | [RabbitMQ server](https://github.com/rabbitmq/rabbitmq-server) and [server license](https://github.com/rabbitmq/rabbitmq-server/blob/main/deps/rabbit/LICENSE). Current scope is only image post-processing. |
| Nacos Server 3.1.1 | Local Config/Naming runtime | Apache License 2.0 | [Nacos](https://github.com/alibaba/nacos), [Nacos Docker](https://github.com/nacos-group/nacos-docker), and [license](https://github.com/alibaba/nacos/blob/develop/LICENSE). Runs standalone in `microservice` mode with embedded storage. The version follows the Spring Cloud Alibaba 2025.1.0.0 matrix and must be rechecked with a future BOM upgrade. |
| Sentinel Dashboard 1.8.9 | Local flow-control learning dashboard | Apache License 2.0 | [Sentinel](https://github.com/alibaba/Sentinel), [release](https://github.com/alibaba/Sentinel/releases/tag/1.8.9), and [license](https://github.com/alibaba/Sentinel/blob/master/LICENSE). Alibaba does not publish a maintained official Dashboard image, so the repository builds a small local image from the official release JAR instead of trusting a third-party image. Rules/monitoring are not made persistent by this container. |
| Eclipse Temurin 17 JRE | Runtime base for the locally built Sentinel image | OpenJDK GPLv2 with Classpath Exception; retain distribution notices | [Eclipse Temurin](https://adoptium.net/) and [container repository](https://github.com/adoptium/containers). Used only as the Dashboard JRE base. |
| curl container | Downloads the pinned official Sentinel JAR in the build stage | curl license (MIT/X-style) | [curl container](https://github.com/curl/curl-container) and [curl license](https://curl.se/docs/copyright.html). It is a build stage and is not present in the final Dashboard image. |
| Swagger UI 5.32.11 | Local browser rendering and manual execution of the OpenAPI contract | Apache License 2.0 | [Swagger UI](https://github.com/swagger-api/swagger-ui), [Docker installation](https://github.com/swagger-api/swagger-ui/blob/main/docs/usage/installation.md), and [license](https://github.com/swagger-api/swagger-ui/blob/main/LICENSE). The official image mounts the repository contract read-only; it does not become a second source of truth. |

Docker images and package versions are pinned in the Compose file or lockfile, which is the authoritative version inventory. “Latest” tags should not be introduced silently.

## Considered 3D alternatives

### `threejs-miniprogram` — fallback

- Source: [wechat-miniprogram/threejs-miniprogram](https://github.com/wechat-miniprogram/threejs-miniprogram)
- License: MIT.
- Strength: official WeChat ecosystem adapter with scoped Three.js and a small example.
- Constraint: the published adapter is based on Three.js r108; loader/control integration is older and less convenient for the selected GLB workflow.
- Decision: retain as a fallback reference, not a bundled dependency.

### XR-Frame — platform-specific fallback

- Source: [WeChat XR-Frame documentation](https://developers.weixin.qq.com/miniprogram/dev/component/xr-frame/overview/)
- License/terms: WeChat platform component terms, not an independently bundled OSS package.
- Strength: official mini-program 3D/XR runtime and potentially better platform integration.
- Constraint: a deeper platform-specific component boundary and less direct reuse of the chosen Three.js hotspot/snapshot implementation.
- Decision: evaluate only if the Three adapter fails the device acceptance checklist. Do not maintain both runtimes prematurely.

### Newer `platformize-three` — migration candidate

- Source: [deepkolos/platformize](https://github.com/deepkolos/platformize/tree/main/packages/platformize-three)
- License: verify the exact package/version before adoption.
- Strength: successor direction named by the current adapter upstream.
- Constraint: migration and uni-app build behavior need a fresh device spike; a newer abstraction is not automatically lower risk.
- Decision: not included in the first working slice.

## Considered annotation alternative

### LeaferJS mini-app packages — backup, not currently bundled

- Source: [LeaferJS](https://github.com/leaferjs/leafer) and [mini-app guide](https://www.leaferjs.com/ui/guide/install/leafer/miniapp/start.html)
- License: MIT.
- Strength: mature scene graph, selection and drawing primitives.
- Constraint: adds bundle/runtime complexity to a deliberately small point/circle/freehand editor, and its WeChat integration would still need uni-app lifecycle/device verification.
- Decision: use native Canvas 2D first. Reconsider if future requirements grow to editing many shapes, transforms, labels and complex selection handles.

## Resource acceptance rules

Before adding or replacing a model, image, icon set or library:

1. record the immutable source URL, author, license and download date;
2. confirm redistribution and modification rights, not merely “free download” wording;
3. retain the original checksum outside runtime assets when practical;
4. record conversion, decimation or rendering steps;
5. check compressed package size and low-end device memory;
6. update this document and dependency notices;
7. rerun the WeChat simulator and physical-device visual checklist.
