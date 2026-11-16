"""Round 4 / E9: rebuild real traces from the locally retained span records of one lab run.

Usage: python assessment/e9/extract-traces.py <run-directory> <output.json> [payment-id]

Reads the bank (`bank-*.stdout.log`) and simulator (`sim-*.stdout.log`) JSON log lines of an E6/E9 lab run, keeps the
records with event.name == trace.span, and writes:
  - every span (trace id, span id, parent id, name, kind, status, duration, links, attributes),
  - one settlement chain: approval -> instruction dispatch -> simulator request -> callback dispatch -> bank callback ->
    financial effect, stitched only through recorded parent ids and links (never guessed); with a payment id the chain
    is walked backward from that payment's after-commit settlement span, otherwise forward from the last approval,
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


CHAIN_STEPS = 7


def span_id_of(traceparent: str) -> str:
    return traceparent.split('-')[2] if traceparent and traceparent.count('-') == 3 else ''


def payment_chain(all_spans: list[dict], payment_id: str) -> list[dict]:
    """Walks backward from the payment's after-commit settlement span, using only recorded parent ids and links."""
    by_id = {s['spanId']: s for s in all_spans}

    def linked(span: dict) -> dict | None:
        return next((by_id[span_id_of(link)] for link in span['links'] if span_id_of(link) in by_id), None)

    effects = [s for s in all_spans if s['name'] == 'clearing.financial_effect' and s['system'] == 'bank'
               and s['attributes'].get('payment.id') == payment_id and s['attributes'].get('effect.path') == 'after_commit']
    for effect in effects:
        callback = by_id.get(effect['parentId'])
        if not callback or callback['system'] != 'bank' or callback['kind'] != 'SERVER':
            continue
        producer = by_id.get(callback['parentId'])
        if not producer or producer['system'] != 'simulator' or producer['name'] != 'callback.dispatch':
            continue
        sim_server = linked(producer)
        if not sim_server or sim_server['system'] != 'simulator' or sim_server['kind'] != 'SERVER':
            continue
        client = by_id.get(sim_server['parentId'])
        if not client or client['system'] != 'bank' or client['kind'] != 'CLIENT':
            continue
        consumer = by_id.get(client['parentId'])
        if not consumer or consumer['name'] != 'outbox.dispatch clearing.instruction.ready':
            continue
        approval = linked(consumer)
        if not approval or approval['kind'] != 'SERVER' or not approval['name'].endswith('/approve'):
            continue
        labels = ['bank: payment approval (books the payable, commits clearing.instruction.ready)',
                  'bank: new trace for the dispatch, linked to the approval span',
                  'bank: client span of the signed simulator request',
                  'simulator: server span continues the bank trace',
                  'simulator: callback delivery in its own trace, linked to the request',
                  'bank: callback request continues the simulator delivery trace',
                  'bank: after-commit settlement inside the callback trace']
        steps = [dict(span, step=label) for span, label in
                 zip([approval, consumer, client, sim_server, producer, callback, effect], labels)]
        for span in all_spans:
            if (span['name'] == 'clearing.financial_effect' and span['attributes'].get('payment.id') == payment_id
                    and span['attributes'].get('effect.path') == 'outbox'):
                parent = by_id.get(span['parentId'])
                steps.append(dict(span, step='bank: durable work-item attempt' + (f" (dispatch {parent['status']})" if parent else '')))
        return steps
    return []


def chain(all_spans: list[dict], payment_id: str | None) -> list[dict]:
    if payment_id:
        return payment_chain(all_spans, payment_id)
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
    return steps


def main() -> int:
    run_dir, output = sys.argv[1], sys.argv[2]
    payment_id = sys.argv[3] if len(sys.argv) > 3 else None
    all_spans = spans(run_dir)
    work = [s for s in all_spans if s['name'] in ('outbox.dispatch clearing.callback.accepted',)]
    result = {'runDirectory': os.path.basename(run_dir.rstrip('/\\')), 'paymentId': payment_id, 'spanCount': len(all_spans),
              'traceCount': len({s['traceId'] for s in all_spans}),
              'settlementChain': chain(all_spans, payment_id),
              'chainStepsRequired': CHAIN_STEPS,
              'clearingWorkItemAttempts': [{'traceId': s['traceId'], 'status': s['status'], 'attempt': s['attributes'].get('outbox.attempt'),
                                            'outcome': s['attributes'].get('outbox.outcome'), 'links': s['links'], 'end': s['end']}
                                           for s in work],
              'spans': all_spans}
    steps = result['settlementChain']
    # Complete only if the first seven steps (approval .. financial effect) were all found through recorded parent ids
    # and links, and every span on the chain ended OK.
    result['chainComplete'] = len(steps) >= CHAIN_STEPS and all(s.get('status') == 'OK' for s in steps[:CHAIN_STEPS])
    with open(output, 'w', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(f"{result['spanCount']} spans, {result['traceCount']} traces, chain of {len(result['settlementChain'])} steps "
          f"(complete={result['chainComplete']}), "
          f"{len(result['clearingWorkItemAttempts'])} work-item attempts")
    return 0


if __name__ == '__main__':
    sys.exit(main())
