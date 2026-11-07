---
title: "Từ repository đến production: CI/CD với Jenkins, GitHub, Docker Hub, K3s, Argo CD, Helm và Vault"
slug: cicd-pipeline-k3s-jenkins-argocd-vault
status: draft
language: vi
tags:
  - cicd
  - jenkins
  - kubernetes
  - k3s
  - argocd
  - helm
  - vault
updated: 2026-11-07
---

# Từ repository đến production: CI/CD với Jenkins, GitHub, Docker Hub, K3s, Argo CD, Helm và Vault

Đây là nhật ký triển khai một pipeline CI/CD có thể tái sử dụng cho nhiều service. Bài viết được cập nhật theo từng chặng: chỉ chuyển một mục sang `done` sau khi chặng đó đã chạy thành công và có bằng chứng kiểm tra.

## Mục tiêu và phạm vi

Pipeline mẫu sẽ đưa một ứng dụng Spring Boot từ GitHub đến một cụm K3s một node trên Ubuntu Server. Jenkins chỉ truy cập qua Tailscale và chủ động polling GitHub, vì vậy không cần mở webhook công khai.

Phạm vi demo:

- GitHub là nguồn mã duy nhất.
- Một repository ứng dụng, một repository tập trung Jenkinsfile, và một repository GitOps chứa Helm chart/values.
- Jenkins chạy test, build image, đẩy image lên Docker Hub và cập nhật image tag trong repository GitOps.
- Argo CD theo dõi repository GitOps rồi đồng bộ workload vào K3s.
- Vault cấp secret cho Jenkins và cung cấp một secret nhỏ cho ứng dụng.
- Loại dependency JPA chưa dùng để test không còn phụ thuộc datasource.

## Kiến trúc

```text
Developer
   |
   v
GitHub: app-repo ----------------------+
                                        | Jenkins polling (Tailscale)
GitHub: pipeline-repo (Jenkinsfile) --> Jenkins
                                        | test -> build -> push
                                        v
                                  Docker Hub
                                        |
                                        +--> GitHub: gitops-repo (Helm values/image tag)
                                                   |
                                                   v
                                             Argo CD
                                                   |
                                                   v
                                            K3s (1 node)
                                                   |
                                                   v
                                              Application
                                                   ^
                                                   |
                                                  Vault
```

Jenkins đọc Jenkinsfile từ repository tập trung. Khi onboarding service mới, ta tạo/cấu hình job với repository ứng dụng, đường dẫn Jenkinsfile, tên image Docker Hub và đường dẫn manifest GitOps; không sao chép Jenkinsfile vào từng repository ứng dụng.

## Các repository

| Repository | Nội dung | Ai cập nhật |
|---|---|---|
| `app-repo` | Mã nguồn, test, Dockerfile và metadata build cần thiết | Developer |
| `pipeline-repo` | Jenkinsfile dùng chung và quy ước tham số onboarding | Platform/Jenkins |
| `gitops-repo` | Helm chart, values theo môi trường, Argo CD Application | Jenkins/Platform |

## Tiến độ triển khai

Trạng thái dùng: `pending`, `in-progress`, `done`, `blocked`.

- [x] `done` — 1. Chuẩn bị ứng dụng và làm xanh test
- [x] `done` — 2. Tạo ba repository GitHub và quy ước branch
- [x] `done` — 3. Chuẩn bị Ubuntu Server, Tailscale và quyền SSH
- [x] `done` — 4. Cài K3s một node và kiểm tra cluster
- [x] `done` — 5. Cài Helm và triển khai Vault
- [x] `done` — 6. Cài Jenkins, cấu hình credential và polling GitHub
- [ ] `in-progress` — 7. Tạo pipeline dùng Jenkinsfile tập trung
- [x] `done` — 8. Đóng gói image và đẩy lên Docker Hub
- [ ] `pending` — 9. Tạo GitOps repository, Helm chart và Argo CD Application
- [ ] `pending` — 10. Inject secret Vault vào ứng dụng
- [ ] `pending` — 11. Chạy end-to-end và kiểm tra rollback
- [ ] `pending` — 12. Đóng gói lại thành bài viết hoàn chỉnh

## Nhật ký từng bước

### Bước 1 — Làm xanh baseline của ứng dụng

**Trạng thái:** `done`

Mục tiêu là chạy được test từ repository hiện tại bằng `./mvnw test`. Dependency JPA chưa được sử dụng sẽ được loại bỏ; phần demo Vault về sau chỉ cần một secret nhỏ trong ứng dụng.

**Thao tác dự kiến:**

1. Đọc `pom.xml`, application config và test hiện có.
2. Xóa dependency JPA chưa dùng cùng cấu hình datasource liên quan (nếu có).
3. Chạy `./mvnw test` và lưu kết quả.

**Bằng chứng cần ghi:**

```text
Ngày xác nhận: 2026-11-07
Lệnh kiểm tra: ./mvnw test
Kết quả: PASS
Lệnh khởi động: ./mvnw spring-boot:run
Kết quả khởi động: BUILD SUCCESS; Tomcat lắng nghe cổng 8080.
Kiểm tra health check: curl http://127.0.0.1:8080/api/v1/ping -> HTTP 200; response {"healthy":"pong"}.
Commit hash: chưa có vì repository GitHub chưa được tạo.
```

Dependency JPA runtime và dependency test JPA đã được loại bỏ vì ứng dụng chưa sử dụng database. Lỗi ban đầu là Spring Boot không xác định được datasource phù hợp.

### Bước 2 — Tạo repository trên GitHub

**Trạng thái:** `done`

Tạo `app-repo`, `pipeline-repo` và `gitops-repo` trong GitHub. Không dùng Bitbucket trong luồng này.

**Bằng chứng cần ghi:**

```text
Ngày hoàn tất: 2026-11-07
app-repo: https://github.com/chinhlason/demo-cicd-backend-app
app commit: 9d8c7405cc5ea6168dec42440e5a41ed89763aba
pipeline-repo: https://github.com/chinhlason/demo-cicd-pipeline
pipeline commit: 8ba42fe35d68333039a654e9ec836f1a6aead2bd
gitops-repo: https://github.com/chinhlason/demo-cicd-gitops
gitops commit: 50739ea2cb8b027aa9940c94576adfe99b5681e7
branch: main
remote verification: git ls-remote xác nhận cả ba repo có refs/heads/main.
```

Các repository được tạo thành ba thư mục Git độc lập. Repo ứng dụng chứa source và file blog; hai repo còn lại bắt đầu bằng README tối thiểu. Push dùng SSH remote vì tài khoản GitHub đã xác thực bằng SSH.

### Bước 3 — Chuẩn bị server Ubuntu qua Tailscale

**Trạng thái:** `done`

Server triển khai: `sonnvt@100.101.40.114`. Jenkins và các thành phần vận hành chỉ được truy cập trên mạng Tailscale. Cần xác nhận SSH key, hostname, dung lượng đĩa, Docker/container runtime và quyền sudo trước khi cài đặt.

**Bằng chứng cần ghi:**

```text
Ngày hoàn tất: 2026-11-07
user: sonnvt
hostname: sonnvt
OS: Ubuntu 24.04
CPU: 16
memory: 15 GiB RAM, 4 GiB swap
disk: 468 GiB total, 292 GiB available on /
SSH: user xác nhận đăng nhập thành công qua Tailscale
Tailscale: 100.101.40.114 / sonnvt-1 đang online
sudo: có quyền sudo nhưng yêu cầu nhập mật khẩu
```

Các bước cài đặt tiếp theo sẽ chạy trong phiên SSH tương tác để có thể nhập mật khẩu sudo khi cần.

### Bước 4 — Cài K3s một node

**Trạng thái:** `done`

Cài K3s trên Ubuntu Server, kiểm tra node ở trạng thái `Ready`, kubeconfig và namespace dành cho các workload demo.

**Bằng chứng cần ghi:**

```text
Ngày hoàn tất: 2026-11-07
K3s version: v1.37.2+k3s1
systemctl is-active k3s: active
node: sonnvt / Ready / control-plane
internal IP: 192.168.0.103
container runtime: containerd://2.3.7-k3s1
system pods: CoreDNS, local-path-provisioner, metrics-server và Traefik đều Running.
install jobs: Gateway API CRD và Traefik CRD ở trạng thái Completed.
```

Cluster một node đã sẵn sàng cho các workload tiếp theo. Pod cài đặt Traefik có một lần restart trong lúc khởi động nhưng kết thúc ở trạng thái `Running`.

**Log kiểm tra thực tế:**

```text
$ sudo systemctl is-active k3s
active

$ sudo k3s kubectl get nodes -o wide
NAME     STATUS   ROLES           AGE   VERSION        INTERNAL-IP     EXTERNAL-IP   OS-IMAGE             KERNEL-VERSION             CONTAINER-RUNTIME
sonnvt   Ready    control-plane   68s   v1.37.2+k3s1   192.168.0.103   <none>        Ubuntu 24.04.2 LTS   7.0.0-28-generic (amd64)   containerd://2.3.7-k3s1

$ sudo k3s kubectl get pods -A
NAMESPACE     NAME                                      READY   STATUS      RESTARTS      AGE
kube-system   coredns-577d995dff-52bmx                  1/1     Running     0             63s
kube-system   helm-install-gateway-api-crd-7n46g        0/1     Completed   0             63s
kube-system   helm-install-traefik-crd-zwvdq            0/1     Completed   0             63s
kube-system   helm-install-traefik-rm6nn                0/1     Completed   1 (39s ago)   63s
kube-system   local-path-provisioner-56c6584955-whwtd   1/1     Running     0             63s
kube-system   metrics-server-778c4649b4-mjt9p           1/1     Running     0             63s
kube-system   svclb-traefik-cd6ef3b1-lrsf4              2/2     Running     0             37s
kube-system   traefik-68df944ff7-ck68v                  1/1     Running     0             37s
```

### Bước 5 — Cài Helm và Vault

**Trạng thái:** `done`

Cài Helm, triển khai Vault bằng chart phù hợp với môi trường demo và ghi rõ mode chạy, storage, unseal và policy. Token/root credential không được commit vào repository hoặc đưa vào bài viết.

**Bằng chứng cần ghi:**

```text
Ngày bắt đầu: 2026-11-07
Helm: v3.22.1
Checksum: verified by installer
helm status vault -n vault: deployed, revision 1
vault status: Initialized true, Sealed false
policy/secret paths used: [redacted description]
```

**Log cài Helm:**

```text
Downloading https://get.helm.sh/helm-v3.22.1-linux-amd64.tar.gz
Verifying checksum... Done.
Preparing to install helm into /usr/local/bin
helm installed into /usr/local/bin/helm
version.BuildInfo{Version:"v3.22.1", GitCommit:"d6c1a0e4027caa6abc7892e7ea9f0cda7447baa3", GitTreeState:"clean", GoVersion:"go1.26.9"}
```

**Lần dry-run Vault đầu tiên:** thất bại vì Helm chưa dùng kubeconfig chuẩn của K3s:

```text
Error: INSTALLATION FAILED: Kubernetes cluster unreachable: Get "https://127.0.0.1:6443/version": tls: failed to verify certificate: x509: certificate signed by unknown authority
```

Nguyên nhân là context/kubeconfig của Helm chưa trỏ tới `/etc/rancher/k3s/k3s.yaml`. Không tắt kiểm tra TLS để xử lý lỗi này.

Sau khi sửa kubeconfig, lệnh dry-run có in phần `NOTES` của Vault nhưng không tạo release. Kiểm tra xác nhận:

```text
$ helm status vault -n vault
Error: release: not found

$ kubectl get pods,pvc -n vault
No resources found in vault namespace.
```

Dry-run chỉ kiểm tra manifest; cần chạy lại `helm install` không có cờ `--dry-run` để triển khai thật.

**Log cài Vault thật:**

```text
Helm release: vault
namespace: vault
chart: hashicorp/vault:0.34.1
status: deployed
revision: 1
PVC data-vault-0: Bound, 10Gi, local-path, RWO
vault-agent-injector: Running
vault-0: ContainerCreating trong lần kiểm tra đầu tiên
```

Vault release đã được tạo thành công và PVC đã bind. Cần chờ `vault-0` chuyển sang `Running` trước khi initialize/unseal.

**Kiểm tra sau khi pod khởi động:**

```text
vault-0: 0/1 Running, 0 restarts
vault-agent-injector: 1/1 Running, 0 restarts
data-vault-0: Bound, 10Gi, local-path, RWO
```

Container Vault đã chạy nhưng chưa `Ready`; bước tiếp theo là kiểm tra trạng thái rồi initialize và unseal. Không ghi unseal key hoặc root token vào blog.

**Log trạng thái Vault trước khi initialize:**

```text
Seal Type: shamir
Initialized: false
Sealed: true
Total Shares: 0
Threshold: 0
Version: 2.0.4
Storage Type: file
HA Enabled: false
```

Lệnh trả exit code `2` vì Vault chưa được initialize; đây là trạng thái mong đợi ở lần cài đầu tiên.

**Kết quả initialize:**

```text
Vault initialized successfully
key shares: 5
key threshold: 3
unseal keys/root token: không ghi vào tài liệu
```

Các key và root token phải được lưu ngoài Git, chat và blog. Nếu credential thật đã bị chia sẻ, cần thay thế chúng trước khi dùng môi trường production.

**Kết quả unseal:**

```text
Unseal Progress: 1/3 -> 2/3 -> 3/3
Initialized: true
Sealed: false
Total Shares: 5
Threshold: 3
Storage Type: file
HA Enabled: false
vault-0: 1/1 Running
vault-agent-injector: 1/1 Running
```

Unseal chỉ mở khóa Vault để phục vụ request; chưa tạo secret engine, policy hoặc quyền truy cập cho Jenkins và ứng dụng.

**KV secret demo:**

```text
engine: app-secrets (kv-v2)
path: app-secrets/data/democicd
version: 1
read-back: thành công
secret value: không ghi vào tài liệu
```

Secret đã được ghi và đọc lại bằng Vault CLI. Bước tiếp theo là tạo policy giới hạn quyền đọc cho workload ứng dụng.

**Policy cho ứng dụng:**

```text
policy: democicd-app
path: app-secrets/data/democicd
capabilities: [read]
result: uploaded and read back successfully
```

Policy chỉ cho phép đọc secret demo; không có quyền ghi hoặc xóa.

**Kiểm tra Kubernetes Auth prerequisite:**

```text
Vault server service account: vault
create tokenreviews as system:serviceaccount:vault:vault: yes
```

Service account của Vault có quyền làm token reviewer; có thể cấu hình Kubernetes auth mà không cấp thêm quyền.

**Kubernetes Auth và role cho ứng dụng:**

```text
auth method: kubernetes/
config: written successfully
role: democicd-app
bound service account: democicd-app
bound namespace: democicd
policy: democicd-app
token TTL: 1h
```

Vault cảnh báo role chưa khai báo JWT audience. Demo vẫn hoạt động; production nên cấu hình audience khớp với service account token để tăng kiểm tra claim.

**Tạo workload identity và kiểm tra login end-to-end:**

```text
namespace: democicd
service account: democicd-app
kubectl get nodes: sonnvt Ready
Vault Kubernetes login: ok
JWT/client token: không ghi vào tài liệu
```

ServiceAccount của ứng dụng khớp với role Vault cả về tên và namespace. Login thành công xác nhận Vault có thể xác thực JWT do Kubernetes cấp và cấp token theo policy `democicd-app`.

### Bước 6 — Cài Jenkins và cấu hình polling GitHub

**Trạng thái:** `done`

Cài Jenkins trên server, chỉ expose qua Tailscale. Cấu hình credential tối thiểu cho GitHub, Docker Hub, GitOps repository và Vault. Job dùng SCM polling thay vì webhook công khai.

**Bằng chứng cần ghi:**

```text
Jenkins URL (Tailscale): http://100.101.40.114:18080
service: active
listen: 100.101.40.114:18080 (Java, Tailscale)
setup wizard: completed
dashboard: opened successfully
admin username: không ghi vào tài liệu
polling interval: [value]
credential IDs (names only): github-https
polling: configured with `H/5 * * * *`
```

**Log cài đặt Jenkins:**

```text
Java: OpenJDK 21
Jenkins LTS: cài bằng Debian repository chính thức
systemctl is-active jenkins: active
LISTEN: 100.101.40.114:18080, process java
```

Port mặc định `8080` đã được `blog-api-1` sử dụng và `8081` đã được `blog-web-1` sử dụng. Jenkins được cấu hình qua systemd drop-in với `JENKINS_PORT=18080`, `JENKINS_LISTEN_ADDRESS=100.101.40.114` và `JENKINS_OPTS=--httpPort=18080 --httpListenAddress=100.101.40.114`. Truy cập quản trị chỉ qua địa chỉ Tailscale; credential khởi tạo không ghi vào blog.

Jenkins home cũ đã được di chuyển sang thư mục backup trước khi tạo home mới. Setup wizard đã hoàn tất và admin mới đã mở được dashboard. User, password và token không được ghi vào repository.

GitHub credential đã được tạo trong Jenkins dưới dạng username/password với ID `github-https`. Personal Access Token không được ghi vào log, blog hoặc Git.

Job `demo-cicd-backend` đã đọc Jenkinsfile từ `demo-cicd-pipeline`; SCM polling và checkout ứng dụng hoạt động. Bước Jenkins setup, credential và polling hoàn tất.

### Bước 7 — Pipeline dùng Jenkinsfile tập trung

**Trạng thái:** `in-progress`

Jenkinsfile trong `pipeline-repo` nhận các tham số onboarding cần thiết, sau đó thực hiện các stage:

1. Checkout `app-repo`.
2. Lấy secret cần thiết từ Vault.
3. Chạy test và package.
4. Build image với tag commit SHA.
5. Push image lên Docker Hub.
6. Cập nhật image tag trong `gitops-repo` và commit thay đổi.

**Bằng chứng cần ghi:**

```text
job: demo-cicd-backend
pipeline source: demo-cicd-pipeline/Jenkinsfile
application checkout: success
Test: success
build result: SUCCESS
```

Lần chạy đầu tiên fail vì agent Jenkins dùng Java 21 trong khi `pom.xml` yêu cầu release 25 (`release version 25 not supported`). Sau khi cài JDK 25 trên server, pipeline chạy thành công. Các stage build image, push Docker Hub và cập nhật GitOps sẽ bổ sung ở các bước tiếp theo.

### Bước 8 — Docker Hub

**Trạng thái:** `in-progress`

Tạo repository image, cấu hình credential dạng token trong Jenkins và kiểm tra image tag bất biến theo commit. Không ghi token vào log.

App repo đã có `Dockerfile` runtime dùng `eclipse-temurin:25-jre`; Docker Hub credential đã được tạo trong Jenkins với ID `dockerhub`. Token không được ghi vào tài liệu.

**Bằng chứng cần ghi:**

```text
image: [dockerhub-user/image]
credential ID: dockerhub
tag: short commit SHA (12 ký tự)
push: success
digest: chưa ghi nhận từ build log
```

Jenkins đã package ứng dụng, build Docker image từ `Dockerfile` và push thành công lên Docker Hub. Jenkins agent được cấp quyền dùng Docker socket; Docker token không xuất hiện trong log hoặc tài liệu.

### Bước 9 — GitOps, Helm và Argo CD

**Trạng thái:** `pending`

`gitops-repo` chứa chart tối thiểu cho Deployment, Service, Secret reference và values theo môi trường. Argo CD Application theo dõi repository này; Jenkins chỉ thay đổi image tag, còn Argo CD chịu trách nhiệm đồng bộ vào K3s.

**Bằng chứng cần ghi:**

```text
argocd app get [name]: [output]
sync status/health: [value]
deployed image: [value]
```

### Bước 10 — Secret Vault trong ứng dụng

**Trạng thái:** `pending`

Ứng dụng đọc một secret demo do Vault cung cấp qua cơ chế phù hợp với chart (ví dụ Vault Agent Injector). Secret không nằm trong GitOps values. Ghi lại đường đi của secret, quyền đọc và cách xác minh mà không lộ giá trị thật.

**Bằng chứng cần ghi:**

```text
pod annotations/mount: [redacted output]
application health/log check: [output]
```

### Bước 11 — Kiểm tra end-to-end và rollback

**Trạng thái:** `pending`

Tạo một commit thay đổi nhỏ, chờ Jenkins polling, kiểm tra image mới, GitOps commit, Argo CD sync và ứng dụng đang chạy. Sau đó thử rollback về image tag trước và ghi thời gian, lệnh, kết quả.

**Bằng chứng cần ghi:**

```text
commit -> running pod timeline: [timestamps]
rollback result: [output/link]
```

## Quy trình cập nhật bài viết

Sau mỗi chặng, chỉ cập nhật file này khi người triển khai xác nhận chặng đã hoàn tất:

1. Đổi checkbox và trường `Trạng thái` của chặng sang `done` hoặc `blocked`.
2. Thêm ngày thực hiện, commit hash và lệnh kiểm tra tối thiểu.
3. Thay placeholder bằng output đã được lọc secret; không ghi token, mật khẩu, kubeconfig hoặc giá trị Vault.
4. Ghi lại lỗi thực tế và cách xử lý ở ngay dưới chặng đó.
5. Cập nhật `updated` trong frontmatter.
6. Không đánh dấu chặng tiếp theo là `done` chỉ vì cấu hình đã được viết; cần có bằng chứng chạy thành công.

## Ghi chú an toàn

- Tailscale giảm bề mặt truy cập nhưng không thay thế xác thực và phân quyền.
- Dùng token có scope tối thiểu cho GitHub và Docker Hub.
- Tách policy Vault cho Jenkins và workload ứng dụng.
- Secrets chỉ xuất hiện trong Vault/Jenkins credential store, không xuất hiện trong Git, Helm values hoặc log.
- Đây là môi trường demo trên K3s một node; production cần thêm HA, backup, TLS, rotation, monitoring và disaster recovery.

## Kết luận (sẽ hoàn thiện sau triển khai)

Phần này sẽ được viết lại sau khi pipeline chạy end-to-end, kèm thời gian triển khai, các lỗi thực tế và những quyết định khác với thiết kế ban đầu.
