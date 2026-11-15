"""Round 4 / E9: rebuild real traces from the locally retained span records of one lab run.

Usage: python assessment/e9/extract-traces.py <run-directory> <output.json> [payment-id]

Reads the bank (`bank-*.stdout.log`) and simulator (`sim-*.stdout.log`) JSON log lines of an E6/E9 lab run, keeps the
records with event.name == trace.span, and writes:
  - every span (trace id, span id, parent id, name, kind, status, duration, links, attributes),
  - one settlement chain: approval -> instruction dispatch -> simulator request -> callback dispatch -> bank callback ->
    financial effect, stitched only through recorded parent ids and links (never guessed),
  - the delivery attempts of the clearing work items (failed attempts and the later success), each a separate trace.
"""
from __future__ import annotations

import glob
import json
import os
import sys


def spans(run_dir: str) -> list[dict]:
    out = []
    for path in sorted(glob.glob(os.path.join(run_dir, 'bank-*.stdout.log')) + glob.glob(os.path.join(run_dir, 'sim-*.stdout.log'))):
        system = 'simulator' if os.path.basename(path).startswith('sim-') else 'bank'
        with open(path, encoding='utf-8', errors='replace') as handle:
            for line in handle:
                line = line.strip()
                if '"trace.span"' not in line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if record.get('event.name') != 'trace.span':
                    continue
                out.append({'system': system, 'end': record.get('span.end'), 'traceId': record.get('trace.id'),
                            'spanId': record.get('span.id'), 'parentId': record.get('span.parent_id') or None,
                            'name': record.get('span.name'), 'kind': record.get('span.kind'), 'status': record.get('span.status'),
                            'durationUs': record.get('span.duration_us'), 'links': record.get('span.links') or [],
                            'attributes': record.get('span.attributes') or {}})
    return sorted(out, key=lambda s: s['end'] or '')


def span_id_of(traceparent: str) -> str:
    return traceparent.split('-')[2] if traceparent and traceparent.count('-') == 3 else ''


def chain(all_spans: list[dict], payment_id: str | None) -> list[dict]:
    by_id = {s['spanId']: s for s in all_spans}
    approve = [s for s in all_spans if s['kind'] == 'SERVER' and s['name'].endswith('/approve') and s['system'] == 'bank'
               and s['attributes'].get('http.response.status_code') == 200]
    if not approve:
        return []
    steps = [dict(approve[-1], step='bank: payment approval (books the payable, commits clearing.instruction.ready)')]
    dispatch = [s for s in all_spans if s['name'] == 'outbox.dispatch clearing.instruction.ready'
                and any(span_id_of(l) == steps[0]['spanId'] for l in s['links'])]
    if dispatch:
        steps.append(dict(dispatch[0], step='bank: new trace for the dispatch, linked to the approval span'))
        client = [s for s in all_spans if s['kind'] == 'CLIENT' and s['traceId'] == dispatch[0]['traceId']
                  and s['name'] == 'POST simulator submit']
        if client:
            steps.append(dict(client[0], step='bank: client span of the signed simulator request'))
            server = [s for s in all_spans if s['system'] == 'simulator' and s['parentId'] == client[0]['spanId']]
            if server:
                steps.append(dict(server[0], step='simulator: server span continues the bank trace'))
                producers = [s for s in all_spans if s['system'] == 'simulator' and s['name'] == 'callback.dispatch'
                             and any(span_id_of(l) == server[0]['spanId'] for l in s['links']) and s['status'] == 'OK']
                if producers:
                    steps.append(dict(producers[0], step='simulator: callback delivery in its own trace, linked to the request'))
                    callback = [s for s in all_spans if s['system'] == 'bank' and s['parentId'] == producers[0]['spanId']]
                    if callback:
                        steps.append(dict(callback[0], step='bank: callback request continues the simulator delivery trace'))
                        effect = [s for s in all_spans if s['name'] == 'clearing.financial_effect' and s['parentId'] == callback[0]['spanId']]
                        if effect:
                            steps.append(dict(effect[0], step='bank: after-commit settlement attempt inside the callback trace'))
    if payment_id:
        durable = [s for s in all_spans if s['name'] == 'clearing.financial_effect'
                   and s['attributes'].get('payment.id') == payment_id and s['attributes'].get('effect.path') == 'outbox']
        for span in durable:
            parent = by_id.get(span['parentId'])
            steps.append(dict(span, step='bank: durable work-item attempt' + (f" (dispatch {parent['status']})" if parent else '')))
    return steps


def main() -> int:
    run_dir, output = sys.argv[1], sys.argv[2]
    payment_id = sys.argv[3] if len(sys.argv) > 3 else None
    all_spans = spans(run_dir)
    work = [s for s in all_spans if s['name'] in ('outbox.dispatch clearing.callback.accepted',)]
    result = {'runDirectory': os.path.basename(run_dir.rstrip('/\\')), 'spanCount': len(all_spans),
              'traceCount': len({s['traceId'] for s in all_spans}),
              'settlementChain': chain(all_spans, payment_id),
              'clearingWorkItemAttempts': [{'traceId': s['traceId'], 'status': s['status'], 'attempt': s['attributes'].get('outbox.attempt'),
                                            'outcome': s['attributes'].get('outbox.outcome'), 'links': s['links'], 'end': s['end']}
                                           for s in work],
              'spans': all_spans}
    with open(output, 'w', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(f"{result['spanCount']} spans, {result['traceCount']} traces, chain of {len(result['settlementChain'])} steps, "
          f"{len(result['clearingWorkItemAttempts'])} work-item attempts")
    return 0


if __name__ == '__main__':
    sys.exit(main())
