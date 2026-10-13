# LookBuddy contracts

Backend Pydantic definitions are the source of truth. Generated JSON schemas use
the HTTP camelCase names and are checked into `schemas/`.

Regenerate after changing an API schema (from `apps/backend`):

```powershell
.\.venv\Scripts\python.exe ..\..\scripts\export_contracts.py
```

Current live API: preferences GET/PATCH, semantic events, snapshots, reminder
responses, intervention display/sample/query, learning state/reward/preview and
persisted scheduler queries. `VisionSampleIn`
drives the P1 temporal action verifier; `ObservationPublic` exposes its evidence.
`ObservationIn` remains a planned generic contract, not a live result-injection API.
P3 exposes `ExpressionIn` / `ExpressionPublic`, `LocalCaptureIn` and
`EmotionCorrectionIn` through expression observation, capture metadata and label
correction APIs. P2 learning uses verified action evidence; P3 expression learning
uses a separate weak-reward version and never proves emotional improvement.

Observations require an offset timestamp and an explicit quality classification.
`live` and `demo` intervention/model namespaces must remain separate. Emotion
correction transfers labels, not captures. Images remain local to the client.
