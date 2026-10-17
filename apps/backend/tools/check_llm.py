"""Run inside the deployed backend; never prints keys, prompts or model output.

python tools/check_llm.py                 # configuration metadata only
python tools/check_llm.py --request       # one small, potentially billable real request
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from lookbuddy.llm_service import complete, model_config, model_status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", action="store_true", help="Send one synthetic prompt to the configured provider")
    args = parser.parse_args()
    status = model_status()
    print(json.dumps({"features": status}, ensure_ascii=False))
    if not all(item["configured"] for item in status.values()):
        print("FAIL: LLM configuration missing; templates are active")
        return 1
    if not args.request:
        print("CONFIGURED: provider connection has not been tested")
        return 0
    try:
        value = asyncio.run(complete(model_config("greeting"), [
            {"role": "system", "content": 'Return only JSON: {"ok":true}'},
            {"role": "user", "content": "Synthetic deployment connectivity check. No user data."}],
            json_output=True, max_tokens=64))
        if json.loads(value) != {"ok": True}:
            print("FAIL: unexpected verification response")
            return 1
    except Exception as error:
        # Do not print exception messages: provider errors can contain request data.
        print("FAIL: provider verification failed (" + type(error).__name__ + ")")
        return 1
    print("PASS: real provider request completed with valid JSON")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
