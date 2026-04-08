# API Contracts (JSON Schema)

백엔드 ↔ 프론트엔드(Chrome Extension, Android App) 간 API 인터페이스 정의

## 구조

```
contracts/
├── common/                          # 공통 타입
│   ├── scores.schema.json           # {profanity, toxicity, hate}
│   └── evidence-span.schema.json    # {text, start, end, score}
├── chrome-extension/                # Chrome Extension 전용
│   ├── analyze-request.schema.json
│   ├── analyze-response.schema.json
│   ├── analyze-batch-request.schema.json
│   └── analyze-batch-response.schema.json
├── android/                         # Android App 전용
│   ├── bounds-in-screen.schema.json
│   ├── android-request.schema.json
│   └── android-response.schema.json
└── examples/                        # 예시 JSON
    ├── analyze-request.json
    ├── analyze-response-offensive.json
    ├── analyze-response-clean.json
    ├── android-request.json
    └── android-response.json
```

## 사용법

### JavaScript / Chrome Extension (ajv)

```javascript
const Ajv = require("ajv");
const ajv = new Ajv();

const schema = require("./chrome-extension/analyze-response.schema.json");
const validate = ajv.compile(schema);

const data = await response.json();
if (!validate(data)) {
  console.error("Invalid response:", validate.errors);
}
```

### Kotlin / Android (networknt json-schema-validator)

```kotlin
val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
val schema = factory.getSchema(
    javaClass.getResourceAsStream("/contracts/android/android-response.schema.json")
)
val errors = schema.validate(responseNode)
```

### Python / Backend (jsonschema)

```python
import json
from jsonschema import validate

with open("contracts/chrome-extension/analyze-response.schema.json") as f:
    schema = json.load(f)

validate(instance=response_data, schema=schema)
```

## 규칙

- 모든 스키마는 **JSON Schema Draft 7** 기준
- `additionalProperties: false` — 정의되지 않은 필드 불가
- `$ref`로 common 스키마 참조 (중복 방지)
- 변경 시 **반드시 팀 공유** 후 업데이트
