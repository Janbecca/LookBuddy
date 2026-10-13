from datetime import datetime, timezone

from lookbuddy.schemas import ExpressionFeatures, ExpressionIn, VisionSampleIn


def test_eufy_web_sdk_is_a_supported_local_vision_source():
    captured_at = datetime.now(timezone.utc)
    sample = VisionSampleIn(
        frameId="frame-eufy-1",
        capturedAt=captured_at,
        source="eufy_web_sdk",
        quality="observable",
        posture="normal",
        presence="present",
    )
    expression = ExpressionIn(
        expressionId="expression-eufy-1",
        sessionId="session-eufy-1",
        capturedAt=captured_at,
        source="eufy_web_sdk",
        quality="observable",
        features=ExpressionFeatures(
            faceVisible=True,
            frontal=True,
            lightingOk=True,
            smile=0,
            browDown=0,
            frown=0,
            mouthPress=0,
            lookDown=0,
            eyeClosed=0,
        ),
    )

    assert sample.source == "eufy_web_sdk"
    assert expression.source == "eufy_web_sdk"
