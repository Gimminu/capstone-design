# capstone-design

모바일 앱 + 브라우저 익스텐션 기반 실시간 텍스트 유해성 필터링 에이전트 프로젝트입니다.

## GitHub 협업 규칙 (필수)

1. 모든 변경은 포크에서 작업 후 PR로 반영
2. `main` 직접 push 금지
3. 승인 규칙: `@Gimminu`/`@haapppy23`는 서로 승인, 다른 팀원 PR은 두 명 중 1명 승인
4. PR 템플릿 작성 필수 + 사람 리뷰로 충돌/오류 확인
5. `main` 머지 시 자동 버전 태그(`v0.0.x`)와 Release 이력 생성
6. 최종 승인/머지 판단은 사람이 수행

## 최소 폴더 구조

```text
android/                  # Android Studio 앱
extension/chrome/         # Chrome Extension
backend/api/              # 유해성 분류 API
backend/tests/            # API 검증/회귀 테스트
shared/normalization/     # 텍스트 정규화
shared/rules/             # 욕설/혐오/스팸 룰 패턴
shared/policy/            # 민감도/카테고리 정책
shared/contracts/         # 앱-익스텐션-백엔드 공통 계약
evaluation/api-vs-ml/     # API vs ML 비교 실험
scripts/new-branch.sh     # 표준 브랜치 생성 보조 스크립트
docs/team-ownership.md    # 팀원별 작업 위치/역할
```

문서:
- [협업 가이드](docs/github-collaboration-guide.md)
- [팀 작업 분담표](docs/team-ownership.md)

브랜치 생성 보조(로컬):

```bash
./scripts/new-branch.sh feat android-text-capture --push
```


## 🚀 Getting Started (설치 및 실행 가이드)

본 유튜브 데이터 파싱 도구(`YouTubeParser`)는 수집된 유해성 데이터를 안전하게 **개인 로컬 서버 PC**로 전송하기 위해 [Tailscale](https://tailscale.com/) 프라이빗 네트워크(VPN) 환경을 사용합니다. 

포트 포워딩 없이, 외부 네트워크(LTE/5G) 환경에서도 안전한 내부망 통신을 보장합니다.

### 📋 Prerequisites (사전 준비)
* 안드로이드 스마트폰 (Android 8.0 이상 권장)
* **Tailscale** 모바일 앱 설치 및 계정 연동
* 데이터를 수신할 로컬 서버 PC (동일한 Tailscale 네트워크에 연결되어 있어야 함)

### 🛠️ Installation & Network Setup (설치 및 네트워크 연동)

**Step 1. 애플리케이션 설치**
* 제공된 `YouTubeParser.apk` 파일을 안드로이드 기기에 설치합니다.

**Step 2. Tailscale 프라이빗 망 연결**
* 구글 플레이스토어에서 **Tailscale** 앱을 다운로드합니다.
* 데이터 수신용 서버 PC와 **동일한 계정**으로 로그인하여 기기를 Tailnet에 등록합니다. (김성현한테 말 하면 로그인 해줌)
* 앱 내 토글을 눌러 VPN을 **Active(활성화)** 상태로 전환합니다.

**Step 3. 서버 IP 동기화 (Endpoint Configuration)**
* 모바일 Tailscale 앱 목록에서, 목적지인 서버 PC에 할당된 **Tailscale 고유 IPv4 주소** (예: `100.x.x.x`)를 확인합니다.
* `YouTubeParser` 앱을 실행한 뒤, 첫 화면의 `서버 주소 저장` 입력란에 해당 IP 주소를 기입하고 저장합니다.
* ssh first@IP 비밀번호 = first

> **💡 Note for Reviewers:** > 이 방식을 통해 공용망에서의 데이터 탈취(Sniffing) 위험을 원천 차단하고, 백엔드 로컬 개발 환경과 모바일 클라이언트 간의 매끄러운 다이렉트 통신 파이프라인을 구축하였습니다.
