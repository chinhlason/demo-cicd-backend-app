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
- [ ] `pending` — 2. Tạo ba repository GitHub và quy ước branch
- [ ] `pending` — 3. Chuẩn bị Ubuntu Server, Tailscale và quyền SSH
- [ ] `pending` — 4. Cài K3s một node và kiểm tra cluster
- [ ] `pending` — 5. Cài Helm và triển khai Vault
- [ ] `pending` — 6. Cài Jenkins, cấu hình credential và polling GitHub
- [ ] `pending` — 7. Tạo pipeline dùng Jenkinsfile tập trung
- [ ] `pending` — 8. Đóng gói image và đẩy lên Docker Hub
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

**Trạng thái:** `pending`

Tạo `app-repo`, `pipeline-repo` và `gitops-repo` trong GitHub. Không dùng Bitbucket trong luồng này.

**Bằng chứng cần ghi:**

```text
app-repo: [URL]
pipeline-repo: [URL]
gitops-repo: [URL]
```

### Bước 3 — Chuẩn bị server Ubuntu qua Tailscale

**Trạng thái:** `pending`

Server triển khai: `sonnvt@100.101.40.114`. Jenkins và các thành phần vận hành chỉ được truy cập trên mạng Tailscale. Cần xác nhận SSH key, hostname, dung lượng đĩa, Docker/container runtime và quyền sudo trước khi cài đặt.

**Bằng chứng cần ghi:**

```text
ssh: [pass/fail]
tailscale connectivity: [pass/fail]
server checks: [output/link]
```

### Bước 4 — Cài K3s một node

**Trạng thái:** `pending`

Cài K3s trên Ubuntu Server, kiểm tra node ở trạng thái `Ready`, kubeconfig và namespace dành cho các workload demo.

**Bằng chứng cần ghi:**

```text
kubectl get nodes: [output]
kubectl get pods -A: [output/link]
```

### Bước 5 — Cài Helm và Vault

**Trạng thái:** `pending`

Cài Helm, triển khai Vault bằng chart phù hợp với môi trường demo và ghi rõ mode chạy, storage, unseal và policy. Token/root credential không được commit vào repository hoặc đưa vào bài viết.

**Bằng chứng cần ghi:**

```text
helm list -A: [output]
vault status: [output]
policy/secret paths used: [redacted description]
```

### Bước 6 — Cài Jenkins và cấu hình polling GitHub

**Trạng thái:** `pending`

Cài Jenkins trên server, chỉ expose qua Tailscale. Cấu hình credential tối thiểu cho GitHub, Docker Hub, GitOps repository và Vault. Job dùng SCM polling thay vì webhook công khai.

**Bằng chứng cần ghi:**

```text
Jenkins URL (Tailscale): [redacted/internal URL]
polling interval: [value]
credential IDs (names only): [list]
```

### Bước 7 — Pipeline dùng Jenkinsfile tập trung

**Trạng thái:** `pending`

Jenkinsfile trong `pipeline-repo` nhận các tham số onboarding cần thiết, sau đó thực hiện các stage:

1. Checkout `app-repo`.
2. Lấy secret cần thiết từ Vault.
3. Chạy test và package.
4. Build image với tag commit SHA.
5. Push image lên Docker Hub.
6. Cập nhật image tag trong `gitops-repo` và commit thay đổi.

**Bằng chứng cần ghi:**

```text
build number: [value]
commit SHA: [value]
stage result: [output/link]
```

### Bước 8 — Docker Hub

**Trạng thái:** `pending`

Tạo repository image, cấu hình credential dạng token trong Jenkins và kiểm tra image tag bất biến theo commit. Không ghi token vào log.

**Bằng chứng cần ghi:**

```text
image: [dockerhub-user/image]
tag: [commit SHA]
digest: [sha256 digest]
```

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
