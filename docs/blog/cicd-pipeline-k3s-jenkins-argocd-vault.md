---
title: "Xây dựng CI/CD với Jenkins, GitHub, Docker Hub, K3s, Argo CD, Helm và Vault"
slug: cicd-pipeline-k3s-jenkins-argocd-vault
description: "Hướng dẫn triển khai một pipeline CI/CD thực tế cho Spring Boot: Jenkins đọc Jenkinsfile tập trung, build image theo commit, cập nhật GitOps, đồng bộ Argo CD, inject secret bằng Vault Agent và expose ứng dụng qua Traefik Ingress."
status: draft
language: vi
tags:
  - cicd
  - jenkins
  - github
  - docker
  - kubernetes
  - k3s
  - argocd
  - helm
  - vault
updated: 2026-11-07
---

# Xây dựng CI/CD với Jenkins, GitHub, Docker Hub, K3s, Argo CD, Helm và Vault

Bài viết này ghi lại cách đưa một ứng dụng Spring Boot từ GitHub lên Kubernetes một node bằng Jenkins, Docker Hub và Argo CD. Jenkinsfile được lưu trong một repository tập trung; mỗi service mới chỉ cần tạo job và khai báo các tham số onboarding.

Phần triển khai đã chạy thành công đến Ingress: Argo CD đạt `Synced/Healthy`, pod chạy `2/2 Running`, `/api/v1/ping` và `/api/v1/secret` trả HTTP 200. Bài cũng ghi lại các lỗi thật đã gặp, cách xử lý và những phần còn pending để không biến một demo thành một quy trình có vẻ hoàn hảo nhưng không kiểm chứng được.

> **Tóm tắt nhanh**
> - GitHub là nguồn mã duy nhất; Jenkins polling GitHub qua mạng Tailscale, không cần webhook công khai.
> - Jenkins build image theo commit SHA, push Docker Hub, cập nhật digest trong GitOps repository rồi để Argo CD triển khai.
> - File `application.yml` trong app repo vẫn chạy local; môi trường deploy dùng file cấu hình từ GitOps và secret do Vault Agent render.
> - Jenkins hiện dùng Jenkins Credential Store. Vault đã được tích hợp vào workload ứng dụng; Jenkins đọc credential trực tiếp từ Vault chưa nằm trong demo này.
> - Rollback cuối cùng nên được thực hiện bằng `git revert` trong GitOps repository để Git vẫn là source of truth.

## 1. Kiến trúc và nguyên tắc

```text
Developer
   |
   v
GitHub app-repo -------------------------+
                                         | SCM polling qua Tailscale
GitHub pipeline-repo (Jenkinsfile) ----> Jenkins
                                         | test -> package -> image -> GitOps
                                         v
                                  Docker Hub
                                         |
                                         v
GitHub gitops-repo (Helm chart/values)
                                         |
                                         v
                                      Argo CD
                                         |
                                         v
                                  K3s một node
                                  /          \
                         Traefik Ingress    Vault Agent Injector
                              |                    |
                              v                    v
                         Spring Boot       Vault KV secret
```

### Quyết định chính

- **GitHub** là SCM duy nhất. Bitbucket không được dùng trong luồng cuối cùng.
- **Jenkinsfile** nằm ở `pipeline-repo`, không sao chép vào từng app repo.
- **GitOps repository** giữ Helm chart, file cấu hình non-secret và image digest.
- **Argo CD** tự sync khi GitOps repository thay đổi.
- **Vault** cấp secret cho ứng dụng qua Kubernetes Auth và Vault Agent Injector.
- **Tailscale** giới hạn truy cập Jenkins, Argo CD và Vault; không mở các UI quản trị ra Internet.

### Ba repository

| Repository | Nội dung | Cập nhật bởi |
|---|---|---|
| [`demo-cicd-backend-app`](https://github.com/chinhlason/demo-cicd-backend-app) | Source, test, Dockerfile và bài viết | Developer |
| [`demo-cicd-pipeline`](https://github.com/chinhlason/demo-cicd-pipeline) | Jenkinsfile dùng chung | Platform/Jenkins |
| [`demo-cicd-gitops`](https://github.com/chinhlason/demo-cicd-gitops) | Helm chart, values, config và Argo CD source | Jenkins/Platform |

## 2. Chuẩn bị ứng dụng Spring Boot

Ứng dụng dùng Java 25 và Spring Boot. Dependency JPA chưa được sử dụng đã được bỏ để test không yêu cầu datasource.

Health endpoint:

```text
GET /api/v1/ping
```

Secret endpoint:

```text
GET /api/v1/secret
```

File `src/main/resources/application.yml` vẫn có thể dùng local:

```yaml
spring:
  application:
    name: democicd

app:
  # Chỉ là fallback cho local; production lấy giá trị từ GitOps + Vault.
  secret-message: ${APP_SECRET_MESSAGE:local-development-secret}
```

Kiểm tra local:

```bash
./mvnw test -q
./mvnw package -q -DskipTests
./mvnw spring-boot:run

curl -i http://127.0.0.1:8080/api/v1/ping
```

Kết quả baseline đã xác nhận:

```json
{"healthy":"pong"}
```

## 3. Tạo repository GitHub

Tạo ba repository GitHub và dùng branch `main`:

```text
https://github.com/chinhlason/demo-cicd-backend-app
https://github.com/chinhlason/demo-cicd-pipeline
https://github.com/chinhlason/demo-cicd-gitops
```

Mỗi repository là một Git repository độc lập. App repo chứa source; pipeline repo chứa Jenkinsfile; GitOps repo chứa Helm chart.

Không ghi GitHub PAT vào README, log hoặc blog. PAT chỉ được lưu trong Jenkins và Argo CD credential store.

## 4. Chuẩn bị Ubuntu Server qua Tailscale

Server triển khai:

```text
SSH: sonnvt@100.101.40.114
OS: Ubuntu 24.04
CPU: 16
RAM: 15 GiB
Disk còn trống: khoảng 292 GiB
Tailscale hostname: sonnvt-1
```

Jenkins chỉ listen trên địa chỉ Tailscale. GitHub không gọi webhook trực tiếp vào server; Jenkins tự polling.

## 5. Cài K3s một node

Cài K3s trên Ubuntu Server:

```bash
curl -sfL https://get.k3s.io | sudo sh -
sudo systemctl enable --now k3s
sudo systemctl is-active k3s
sudo k3s kubectl get nodes -o wide
sudo k3s kubectl get pods -A
```

Kết quả đã xác nhận:

```text
k3s: v1.37.2+k3s1
node: sonnvt / Ready / control-plane
container runtime: containerd://2.3.7-k3s1
Traefik: Running
CoreDNS: Running
local-path-provisioner: Running
metrics-server: Running
```

Cấp kubeconfig cho user SSH để chạy `kubectl` và `helm` không cần `sudo`:

```bash
mkdir -p "$HOME/.kube"
sudo cp /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
sudo chown "$USER:$(id -gn)" "$HOME/.kube/config"
chmod 600 "$HOME/.kube/config"
export KUBECONFIG="$HOME/.kube/config"

kubectl get nodes
```

Nếu thấy lỗi `permission denied` với `/etc/rancher/k3s/k3s.yaml`, dùng chính block lệnh trên để copy kubeconfig về `$HOME/.kube/config`.

## 6. Cài Helm và triển khai Vault

Cài Helm:

```bash
curl -fsSL -o /tmp/get_helm.sh \
  https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
chmod 700 /tmp/get_helm.sh
/tmp/get_helm.sh
helm version
```

Kết quả:

```text
Helm v3.22.1
checksum: verified
```

Thêm repository và cài Vault:

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update

helm install vault hashicorp/vault \
  --namespace vault \
  --create-namespace \
  --version 0.34.1
```

Kiểm tra:

```bash
helm status vault -n vault
kubectl get pods,pvc -n vault
```

Lần đầu `vault-0` có thể ở `ContainerCreating` hoặc `Running` nhưng chưa `Ready`; chờ PVC `Bound` và pod `1/1 Running` trước khi thao tác tiếp.

### 6.1 Initialize và unseal

Kiểm tra trước initialize:

```bash
kubectl exec -n vault vault-0 -- vault status
```

Trạng thái ban đầu mong đợi:

```text
Initialized: false
Sealed: true
```

Initialize Vault một lần:

```bash
kubectl exec -it -n vault vault-0 -- vault operator init
```

Kết quả tạo 5 key shares với threshold 3. Không ghi unseal key hoặc Initial Root Token vào Git, chat hay blog.

Unseal bằng ba key khác nhau:

```bash
kubectl exec -it -n vault vault-0 -- vault operator unseal
kubectl exec -it -n vault vault-0 -- vault operator unseal
kubectl exec -it -n vault vault-0 -- vault operator unseal
```

Xác nhận:

```bash
kubectl exec -n vault vault-0 -- vault status
```

Kết quả cần là `Initialized: true` và `Sealed: false`.

Nếu mất Initial Root Token, Vault không lưu lại để đọc lại. Không chạy lại `vault operator init` trên Vault đã initialized. Cần dùng quy trình `vault operator generate-root` với quorum unseal key để tạo root token mới.

### 6.2 Tạo KV secret và policy

Mở shell trong pod Vault:

```bash
kubectl exec -it -n vault vault-0 -- sh
vault login
```

Tạo KV engine và secret demo:

```bash
vault secrets enable -path=app-secrets kv-v2
vault kv put app-secrets/democicd message='hello from vault'
vault kv get app-secrets/democicd
```

Policy chỉ đọc:

```hcl
path "app-secrets/data/democicd" {
  capabilities = ["read"]
}
```

Ghi policy:

```bash
cat >/tmp/democicd-app.hcl <<'POLICY'
path "app-secrets/data/democicd" {
  capabilities = ["read"]
}
POLICY

vault policy write democicd-app /tmp/democicd-app.hcl
vault policy read democicd-app
```

### 6.3 Kubernetes Auth cho workload

Tạo namespace và ServiceAccount:

```bash
kubectl apply -f - <<'KUBEYAML'
apiVersion: v1
kind: Namespace
metadata:
  name: democicd
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: democicd-app
  namespace: democicd
KUBEYAML
```

Kiểm tra Vault ServiceAccount có quyền token review:

```bash
kubectl auth can-i create tokenreviews \
  --as=system:serviceaccount:vault:vault
```

Bật Kubernetes Auth trong Vault pod:

```bash
vault auth enable kubernetes

vault write auth/kubernetes/config \
  token_reviewer_jwt="$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)" \
  kubernetes_host="https://kubernetes.default.svc:443" \
  kubernetes_ca_cert=@/var/run/secrets/kubernetes.io/serviceaccount/ca.crt

vault write auth/kubernetes/role/democicd-app \
  bound_service_account_names=democicd-app \
  bound_service_account_namespaces=democicd \
  policies=democicd-app \
  ttl=1h
```

Kiểm tra login end-to-end mà không ghi JWT ra log:

```bash
JWT="$(kubectl create token democicd-app -n democicd)"

kubectl exec -i -n vault vault-0 -- env KUBE_JWT="$JWT" sh -c '
  vault write -field=token auth/kubernetes/login \
    role=democicd-app \
    jwt="$KUBE_JWT" >/dev/null
'

unset JWT
echo "vault kubernetes login: ok"
```

Vault cảnh báo role chưa có audience. Demo vẫn chạy; production nên cấu hình audience khớp với ServiceAccount token.

## 7. Cài Jenkins và chỉ expose qua Tailscale

Jenkins chạy trên Ubuntu host, truy cập bằng:

```text
http://100.101.40.114:18080
```

### 7.1 Xử lý xung đột port

`8080` đã được `blog-api-1` dùng và `8081` đã được `blog-web-1` dùng. Cấu hình Jenkins ở `18080`:

```bash
sudo install -d /etc/systemd/system/jenkins.service.d
sudo tee /etc/systemd/system/jenkins.service.d/override.conf >/dev/null <<'JENKINSUNIT'
[Service]
Environment="JENKINS_PORT=18080"
Environment="JENKINS_LISTEN_ADDRESS=100.101.40.114"
Environment="JENKINS_OPTS=--httpPort=18080 --httpListenAddress=100.101.40.114"
JENKINSUNIT

sudo systemctl daemon-reload
sudo systemctl reset-failed jenkins
sudo systemctl restart jenkins
sudo systemctl is-active jenkins
sudo ss -ltnp | grep ':18080'
```

Kết quả xác nhận là Java listen trên `100.101.40.114:18080`.

### 7.2 Cấp quyền Docker cho Jenkins

```bash
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
sudo -u jenkins -H docker version
```

Nếu Jenkins chưa khởi động sau khi đổi port, đọc log:

```bash
sudo systemctl status jenkins.service --no-pager -l
sudo journalctl -u jenkins.service -n 120 --no-pager
```

### 7.3 Tạo user và credential

Setup wizard đã được hoàn tất bằng user mới sau khi backup Jenkins home cũ. User cũ không được giữ lại trong demo mới. Không ghi username/password/token vào repository.

Tạo các credential trong Jenkins:

| Credential ID | Loại | Dùng cho |
|---|---|---|
| `github-https` | Username/password | Checkout app và push GitOps |
| `dockerhub` | Username/password | Docker Hub login |
| `argocd-sync-kubeconfig` | Secret file | Patch Argo CD Application |

PAT GitHub phải có quyền đúng với repository cần checkout/push. Lỗi thực tế từng gặp:

```text
remote: Write access to repository not granted
fatal: ... 403
```

Nguyên nhân là credential chưa có quyền ghi vào repository.

## 8. Jenkinsfile tập trung và onboarding service

Jenkinsfile nằm trong `demo-cicd-pipeline`, không nằm trong app repo. Khi onboarding service mới, tạo một Jenkins Pipeline job và khai báo các parameter:

| Parameter | Ví dụ |
|---|---|
| `APP_REPO_URL` | `https://github.com/chinhlason/demo-cicd-backend-app.git` |
| `APP_BRANCH` | `main` |
| `DOCKER_IMAGE` | `sonnvt/demo-cicd-backend-app` |
| `GITOPS_REPO_URL` | `https://github.com/chinhlason/demo-cicd-gitops.git` |
| `GITOPS_BRANCH` | `main` |
| `GITOPS_VALUES_FILE` | `charts/demo-cicd-backend/values.yaml` |
| `ARGOCD_APPLICATION` | `demo-cicd-backend` |

Cấu hình job:

1. **New Item → Pipeline**.
2. Bật **This project is parameterized**.
3. Trong **Pipeline**, chọn **Pipeline script from SCM**.
4. Chọn Git, nhập pipeline repo, credential `github-https`, branch `*/main`, Script Path `Jenkinsfile`.
5. Bật **Poll SCM** với `H/5 * * * *`.
6. Lưu job và chạy **Build with Parameters**.

Lỗi thực tế: dán phần mô tả Markdown vào ô Groovy gây `unexpected token: URL`. Ô Script chỉ nhận Jenkinsfile; mô tả repository phải nằm trong blog hoặc README.

### Các stage

1. `Validate onboarding config`
2. `Checkout application`
3. `Test`
4. `Package`
5. `Build and push image`
6. `Update GitOps`
7. `Sync Argo CD`

Pipeline build image theo short commit SHA, push lên Docker Hub, lấy digest từ image local, cập nhật `values.yaml` rồi push GitOps.

### Phân quyền parameter

Dùng **Project-based Matrix Authorization Strategy**. User chạy job chỉ cần:

- `Overall/Read`
- `Job/Read`
- `Job/Build`
- `Job/Cancel` nếu cần dừng build

Không cấp `Job/Configure`, `Job/Create`, `Job/Delete`, `Credentials/Create` hoặc `Overall/Administer` cho user thường. `Job/Build` vẫn cho phép nhập giá trị parameter khác trong từng lần chạy; nếu cần cố định tuyệt đối, đặt cấu hình trong repository được bảo vệ hoặc thêm allowlist validation trong Jenkinsfile.

### Hiển thị Stage View

Cài plugin **Pipeline: Stage View** (`pipeline-stage-view`), mở job và chọn **Stages**. Jenkins sẽ hiển thị một cột cho mỗi stage và một hàng cho mỗi build.

Nếu cần graph của một build, cài **Pipeline Graph View** rồi mở **Pipeline Overview**. Jenkinsfile hiện tại đã là Declarative Pipeline nên không cần đổi cấu trúc để hiển thị stage.

### Direct sync và Auto-Sync

Stage `Sync Argo CD` giúp Jenkins báo lỗi nếu Application không đạt `Synced/Healthy`, nhưng Jenkins phải giữ kubeconfig có quyền patch Application và phải chờ rollout.

Argo CD Application đang dùng:

```yaml
syncPolicy:
  automated:
    prune: true
    selfHeal: true
```

Vì vậy khi Jenkins push GitOps commit, Argo CD có thể tự sync mà không cần Jenkins gọi API sync trực tiếp. Có thể giữ stage hiện tại để làm quality gate, hoặc bỏ lệnh patch và chỉ chờ trạng thái sau khi Auto-Sync đã ổn định.

## 9. Build và push Docker image

Dockerfile runtime:

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/*SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Jenkins đã build và push image thành công lên Docker Hub. Image được tham chiếu bằng digest, không chỉ bằng tag `latest`.

Với Docker Hub repository private, tạo pull secret:

```bash
read -r -s "DOCKERHUB_PULL_TOKEN?Docker Hub read-only token: "
printf '\n'

kubectl create secret docker-registry dockerhub-pull \
  --namespace democicd \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=sonnvt \
  --docker-password="$DOCKERHUB_PULL_TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -

unset DOCKERHUB_PULL_TOKEN
```

Token cần quyền `Repo Read-only`. Hai lỗi thật đã gặp:

- Token chỉ có `Repo Public Read-only`, không pull được private image.
- Username nhập nhầm `chinhlason` thay vì username Docker Hub `sonnvt`.

Khi secret đúng, pod chuyển từ `ImagePullBackOff` sang `Running`.

## 10. Cài Argo CD và tạo Application

Tạo namespace và cài Argo CD:

```bash
kubectl create namespace argocd \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -n argocd \
  --server-side \
  --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

kubectl wait \
  --for=condition=Ready pod \
  --all \
  -n argocd \
  --timeout=180s

kubectl get pods -n argocd
```

Kết nối GitOps repo trong **Settings → Repositories → Connect Repo using HTTPS**. PAT chỉ nhập vào Argo CD credential store.

Tạo Application:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: demo-cicd-backend
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/chinhlason/demo-cicd-gitops.git
    targetRevision: main
    path: charts/demo-cicd-backend
  destination:
    server: https://kubernetes.default.svc
    namespace: democicd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Áp dụng manifest bằng `kubectl apply -f application.yaml`.

### 10.1 Forward Argo CD UI

Lấy password admin lần đầu:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d; echo
```

Một cách forward qua SSH:

```bash
ssh -N \
  -L 18084:127.0.0.1:18084 \
  sonnvt@100.101.40.114 \
  'KUBECONFIG=/home/sonnvt/.kube/config kubectl -n argocd port-forward --address 127.0.0.1 svc/argocd-server 18084:443'
```

Mở:

```text
https://127.0.0.1:18084
```

Dùng `https`, không dùng `http`. Giữ terminal SSH mở trong thời gian thao tác. Nếu cổng đã bị chiếm, chọn một cổng local khác.

### 10.2 Kiểm tra Application và workload

```bash
kubectl get application demo-cicd-backend -n argocd
kubectl get pods -n democicd
kubectl get svc -n democicd
```

Health endpoint qua port-forward:

```bash
kubectl port-forward -n democicd \
  svc/demo-cicd-backend-demo-cicd-backend 18083:8080

curl -i http://127.0.0.1:18083/api/v1/ping
```

Kết quả đã xác nhận:

```text
Argo CD: Synced / Healthy
pod: 1/1 Running hoặc 2/2 Running khi có Vault Agent
API: HTTP 200
```

## 11. External application config và Vault Agent

Mục tiêu là giữ `application.yml` trong app repo để chạy local, nhưng khi deploy dùng file từ GitOps.

### 11.1 File cấu hình trong GitOps

`charts/demo-cicd-backend/config/application.yml`:

```yaml
spring:
  application:
    name: democicd
  config:
    import: optional:file:/vault/secrets/application-secrets.yml

app:
  secret-message: ${app.secrets.message}
```

Helm chart tạo ConfigMap và mount file vào `/config`. Deployment dùng:

```yaml
env:
  - name: SPRING_CONFIG_ADDITIONAL_LOCATION
    value: "optional:file:/config/"
```

Checksum của file config được đưa vào pod annotation để thay đổi non-secret tạo rollout mới.

### 11.2 Vault annotations

Pod dùng:

```yaml
vault.hashicorp.com/agent-inject: "true"
vault.hashicorp.com/role: democicd-app
vault.hashicorp.com/agent-inject-secret-application-secrets.yml: app-secrets/data/democicd
vault.hashicorp.com/agent-inject-template-application-secrets.yml: |
  {{ with secret "app-secrets/data/democicd" }}
  app:
    secrets: {{ .Data.data | toJSON }}
  {{ end }}
```

Template dùng `toJSON`. Một bản trước dùng hàm `quote`, làm `vault-agent-init` fail với:

```text
function "quote" not defined
```

Sau khi đổi sang `toJSON`, init container authenticate thành công và render file:

```text
/vault/secrets/application-secrets.yml
```

Kiểm tra file mà không in nội dung secret:

```bash
POD="$(kubectl get pods -n democicd -o jsonpath='{.items[0].metadata.name}')"

kubectl exec -n democicd "$POD" -c app -- sh -c \
  'test -s /config/application.yml && \
   test -s /vault/secrets/application-secrets.yml && \
   echo "external config and Vault secret file: ok"'
```

Kiểm tra API:

```bash
kubectl port-forward -n democicd \
  svc/demo-cicd-backend-demo-cicd-backend 18083:8080
curl -i http://127.0.0.1:18083/api/v1/secret
```

Kết quả thực tế sau khi đổi secret trong Vault:

```json
{"message":"hello from vault 2"}
```

### 11.3 Đổi secret qua Vault UI

Forward Vault UI:

```bash
# trên server
kubectl -n vault port-forward svc/vault 18200:8200

# trên máy quản trị
ssh -N -L 18200:127.0.0.1:18200 sonnvt@100.101.40.114
```

Mở `http://127.0.0.1:18200/ui`, đăng nhập bằng Vault token, vào **Secrets → app-secrets → democicd**, sửa key `message` rồi lưu.

Vault Agent có thể render lại file, nhưng Spring bean đã đọc bằng `@Value` không tự refresh. Rollout lại Deployment:

```bash
kubectl rollout restart deployment/demo-cicd-backend-demo-cicd-backend \
  -n democicd
kubectl rollout status deployment/demo-cicd-backend-demo-cicd-backend \
  -n democicd
```

Nếu thêm key mới trong Vault, thêm placeholder tương ứng ở GitOps config:

```yaml
app:
  api-key: ${app.secrets.api-key}
```

## 12. Expose qua Traefik Ingress

K3s đã cài Traefik mặc định. GitOps chart tạo Ingress `networking.k8s.io/v1` với `ingressClassName: traefik`, route `/` đến Service ClusterIP.

```bash
kubectl get ingress -n democicd
kubectl describe ingress -n democicd \
  demo-cicd-backend-demo-cicd-backend
kubectl get svc -n kube-system traefik
kubectl get pods -n kube-system -l app.kubernetes.io/name=traefik
```

Gọi qua Tailscale:

```bash
curl -i http://100.101.40.114/api/v1/ping
curl -i http://100.101.40.114/api/v1/secret
```

Kết quả đã xác nhận HTTP 200. Đường đi là:

```text
Tailscale → node port 80 → Traefik → ClusterIP Service → Pod
```

Ingress hiện chưa có DNS host hoặc TLS. Muốn public Internet cần public IP, firewall, DNS và certificate. Jenkins, Argo CD và Vault vẫn nên giữ trong mạng quản trị.

## 13. Những lỗi đáng chú ý

### Helm dry-run báo cluster unreachable

```text
x509: certificate signed by unknown authority
```

Nguyên nhân là Helm chưa dùng kubeconfig K3s đúng. Dry-run không tạo release; kiểm tra `helm status` và namespace trước khi kết luận đã cài.

### Jenkins không bind được cổng

```text
Failed to bind to 0.0.0.0:8080
```

Kiểm tra port bằng `ss`, sau đó dùng systemd drop-in với port `18080`.

### Jenkins Pipeline lỗi Groovy

```text
unexpected token: URL
```

Nguyên nhân là dán Markdown vào ô Script. Dùng **Pipeline script from SCM** và để Script Path là `Jenkinsfile`.

### Docker Hub `ImagePullBackOff`

Kiểm tra lần lượt:

```bash
kubectl get secret dockerhub-pull -n democicd
kubectl get deployment demo-cicd-backend-demo-cicd-backend \
  -n democicd \
  -o jsonpath='{.spec.template.spec.imagePullSecrets}{"\n"}'
kubectl describe pod -n democicd <pod-name> | sed -n '/Events:/,$p'
```

Repository private cần token `Repo Read-only` và đúng username Docker Hub.

### Argo CD `Synced` nhưng `Progressing`

`Synced` chỉ nói manifest đã khớp Git. Cần chờ Deployment, init container, sidecar và readiness probe đạt `Healthy`. Trong demo, lỗi `quote` không tồn tại làm Vault init container fail; chọn pod đúng bằng tên cụ thể thay vì luôn lấy `.items[0]` trong lúc rolling update.

## 14. Rollback

### 14.1 Rollback đúng theo GitOps

Rollback bền vững nên thực hiện bằng GitOps repository:

```bash
cd demo-cicd-gitops
git pull --ff-only origin main
git log --oneline -- charts/demo-cicd-backend/values.yaml
git show --stat <commit-deploy-code-moi>
git revert <commit-deploy-code-moi>
git push origin main
```

Argo CD sẽ tự sync digest cũ nếu Auto-Sync đang bật. Kiểm tra:

```bash
kubectl get application demo-cicd-backend -n argocd
kubectl rollout status \
  deployment/demo-cicd-backend-demo-cicd-backend \
  -n democicd
curl -i http://100.101.40.114/api/v1/ping
```

### 14.2 Rollback trên Argo CD UI

1. Mở `Applications → demo-cicd-backend`.
2. Tắt **Auto-Sync** tạm thời.
3. Chọn **History and Rollback**.
4. Chọn revision trước bản code mới.
5. Bấm **Rollback** và chờ `Synced/Healthy`.
6. Tạo `git revert` trong GitOps repo sau đó để Git và cluster không lệch.

Rollback UI chỉ là thay đổi live state tạm thời nếu Git vẫn giữ digest mới. Auto-Sync có thể đưa bản mới trở lại.

### 14.3 Trạng thái rollback trong phiên triển khai

Đã tạo commit app `c8a31cd` để đổi health response thành `pong-v2`; test local pass và commit đã push lên `main`. Kết quả kiểm tra qua Ingress vẫn là:

```json
{"healthy":"pong"}
```

trong khi secret đã trả:

```json
{"message":"hello from vault 2"}
```

Điều này xác nhận Vault rollout đã hoạt động, nhưng chưa đủ bằng chứng rằng image `pong-v2` đã chạy trên cluster. Chặng rollback end-to-end vẫn cần xác nhận image digest trong Deployment, Jenkins build và Argo history trước khi đánh dấu hoàn tất.

## 15. Bảo mật và giới hạn của demo

- Tailscale giảm bề mặt truy cập nhưng không thay thế authentication và RBAC.
- GitHub PAT, Docker Hub token, kubeconfig và Vault token chỉ lưu trong credential store an toàn.
- Vault policy của workload chỉ có quyền `read` trên path demo.
- Jenkins hiện dùng Jenkins Credential Store; Vault cho Jenkins chưa được triển khai.
- K3s chỉ có một node, Vault dùng file storage và chưa có HA/backup production.
- Ingress chưa có domain/TLS.
- Image digest được lưu trong GitOps, nhưng rollback cần được kiểm chứng bằng deployment history và API thực tế.

## 16. Checklist kết quả

- [x] Ứng dụng Spring Boot chạy local và test pass.
- [x] Ba repository GitHub được tạo.
- [x] K3s một node `Ready`.
- [x] Helm và Vault được cài, Vault initialized/unsealed.
- [x] Vault KV, policy và Kubernetes Auth cho ứng dụng hoạt động.
- [x] Jenkins chạy ở `100.101.40.114:18080` qua Tailscale.
- [x] Jenkinsfile tập trung từ pipeline repo.
- [x] Jenkins build/test/package/push image.
- [x] GitOps Helm chart và Argo CD Application hoạt động.
- [x] Docker Hub private image pull thành công.
- [x] External application config và Vault Agent hoạt động.
- [x] Ingress trả HTTP 200 qua Tailscale.
- [ ] Xác nhận release `pong-v2` chạy trên cluster.
- [ ] Hoàn tất rollback end-to-end và ghi evidence cuối.
- [ ] Tích hợp Jenkins đọc credential trực tiếp từ Vault.

## Tài liệu tham khảo

- [Jenkins Pipeline](https://www.jenkins.io/doc/book/pipeline/)
- [Jenkins permissions](https://www.jenkins.io/doc/book/security/access-control/permissions/)
- [Pipeline: Stage View](https://plugins.jenkins.io/pipeline-stage-view/)
- [HashiCorp Vault Jenkins Plugin](https://plugins.jenkins.io/hashicorp-vault-plugin/)
- [Vault Agent templates](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent/template)
- [Argo CD automated sync](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/)
- [Argo CD rollback](https://argo-cd.readthedocs.io/en/stable/user-guide/commands/argocd_app_rollback/)
- [K3s documentation](https://docs.k3s.io/)
- [Helm documentation](https://helm.sh/docs/)

## Kết luận

Một pipeline CI/CD có thể tái sử dụng không bắt đầu từ Jenkinsfile dài hơn. Nó bắt đầu từ ranh giới rõ giữa source code, pipeline definition, image và desired state.

Trong demo này, service mới được onboarding bằng các parameter của Jenkins job. Jenkins build image theo commit, GitOps lưu digest, Argo CD triển khai, Vault cấp secret cho workload và Traefik nhận request qua Tailscale.

Các bước còn lại nên được hoàn thiện theo cùng nguyên tắc: kiểm chứng image đang chạy, rollback bằng GitOps commit, sau đó mới đánh dấu pipeline end-to-end là hoàn tất.
