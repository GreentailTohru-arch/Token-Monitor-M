import importlib.util, json, tempfile, time, unittest
import threading, urllib.request, urllib.error
from datetime import datetime, timezone
from pathlib import Path
spec=importlib.util.spec_from_file_location('notifier',Path(__file__).with_name('notifier.py'))
n=importlib.util.module_from_spec(spec);spec.loader.exec_module(n)

def record(kind,payload):
    return dict(type=kind,payload=payload,timestamp=datetime.now(timezone.utc).isoformat())
def turn(turn='turn-1',source='vscode',body='最终回答'):
    return [record('session_meta',dict(id='thread-1',source=source)),
        record('event_msg',dict(type='task_started',turn_id=turn)),
        record('response_item',dict(type='message',role='user',content=[dict(type='input_text',text='帮我修复界面')])),
        record('response_item',dict(type='message',role='assistant',phase='commentary',content=[dict(type='output_text',text='正在检查')])),
        record('event_msg',dict(type='task_complete',turn_id=turn,last_agent_message=body))]

class Tests(unittest.TestCase):
    def test_auth_and_usage_routes_absent(self):
        server=n.RelayServer(('127.0.0.1',0),n.make_handler(n.Events(),'test-secret'))
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            for path,key,status in [('/v1/health','',401),('/v1/events','wrong',401),('/api/stats','test-secret',404)]:
                request=urllib.request.Request(base+path,headers={'Authorization':'Bearer '+key})
                with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(request,timeout=2)
                self.assertEqual(error.exception.code,status)
            request=urllib.request.Request(base+'/v1/health',headers={'Authorization':'Bearer test-secret'})
            with urllib.request.urlopen(request,timeout=2) as response:self.assertTrue(json.load(response)['ok'])
        finally:server.shutdown();server.server_close();thread.join(2)
    def test_completion_pairs_original_prompt_and_final(self):
        r=n.TurnReader();events=[r.accept(x) for x in turn()];self.assertTrue(all(x is None for x in events[:-1]))
        self.assertEqual(events[-1]['title'],'帮我修复界面');self.assertEqual(events[-1]['body'],'最终回答')
        self.assertIsNone(r.accept(turn()[-1]))
    def test_codex_input_uses_actual_question_and_call_id(self):
        for tool, label in [('request_user_input', 'question'), ('request_user_input_async', 'title')]:
            r = n.TurnReader()
            for entry in turn()[:-1]:
                r.accept(entry)
            payload = dict(type='function_call', name=tool, call_id='question-1',
                           arguments=json.dumps({'questions': [{label: '选哪个？'}]}))
            event = r.accept(record('response_item', payload))
            self.assertEqual((event['kind'], event['title'], event['body']), ('input', '帮我修复界面', '选哪个？'))
            self.assertEqual(r.accept(record('response_item', payload))['id'], event['id'])
            self.assertIsNone(r.accept(record('response_item', dict(payload, type='function_call_output'))))
            self.assertEqual(r.accept(turn()[-1])['kind'], 'complete')

    def test_only_structured_codex_quota_and_dedupes_reset_window(self):
        events = n.Events()
        for turn_id in ('turn-1', 'turn-2'):
            r = n.TurnReader()
            for entry in turn(turn_id)[:-1]:
                r.accept(entry)
            self.assertIsNone(r.accept(record('event_msg', dict(type='error', codex_error_info='rate_limit_exceeded',
                                                               message='usage limit exceeded'))))
            quota = dict(type='token_count', rate_limits=dict(limit_id='codex',
                         primary=dict(used_percent=99, resets_at=int(time.time()) + 7200)))
            self.assertIsNone(r.accept(record('event_msg', quota)))
            quota['rate_limits']['rate_limit_reached_type'] = 'rate_limit_reached'
            notice = r.accept(record('event_msg', quota))
            self.assertEqual(notice['kind'], 'quota')
            events.publish(notice)
            self.assertIsNone(r.accept(record('event_msg', dict(type='error', codex_error_info='usage_limit_exceeded'))))
        self.assertEqual(events.sequence, 1)
        r = n.TurnReader()
        for entry in turn()[:-1]:
            r.accept(entry)
        notice = r.accept(record('event_msg', dict(type='error', codex_error_info='usage_limit_exceeded',
                                                   message='Codex quota exhausted')))
        self.assertEqual((notice['kind'], notice['body']), ('quota', 'Codex quota exhausted'))

    def test_abort_and_subagent_do_not_notify(self):
        for source in ['vscode',{'subagent':{'thread_spawn':{}}}]:
            r=n.TurnReader()
            for x in turn(source=source)[:-1]:r.accept(x)
            if isinstance(source,str):r.accept(record('event_msg',dict(type='turn_aborted')))
            else:r.accept(record('session_meta',dict(source='vscode')))
            self.assertIsNone(r.accept(turn()[-1]))
    def test_context_question_and_steering_not_notification_title(self):
        r=n.TurnReader();seq=turn();r.accept(seq[0]);r.accept(seq[1])
        r.accept(record('response_item',dict(type='message',role='user',content=[dict(text='<environment_context>private context</environment_context>')])))
        r.accept(seq[2]);r.accept(record('response_item',dict(type='message',role='user',content=[dict(text='<send_user_message_question_reply>yes</send_user_message_question_reply>')])))
        self.assertEqual(r.accept(seq[-1])['title'],'帮我修复界面')
    def test_empty_and_wrong_turn_do_not_notify(self):
        r=n.TurnReader()
        for x in turn()[:-1]:r.accept(x)
        self.assertIsNone(r.accept(turn('other')[-1]));self.assertIsNone(r.accept(turn(body='')[-1]))
    def test_connect_dedupe_reconnect_and_restart(self):
        e=n.Events();baseline=e.poll('')['cursor'];event={'id':'x','time':time.time(),'title':'t','body':'b'}
        e.publish(event);e.publish(event);result=e.poll(baseline,0)
        self.assertEqual(len(result['events']),1);self.assertEqual(e.poll('')['events'],[])
        self.assertEqual(e.poll(result['cursor'],0)['events'],[])
        self.assertEqual(n.Events().poll(result['cursor'])['events'],[])
    def test_watcher_ignores_history_reads_complete_lines_and_new_turns(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'rollout.jsonl'
            file.write_text(''.join(json.dumps(x)+'\n' for x in turn()),encoding='utf8')
            e=n.Events();w=n.Watcher(d,e);w.scan();baseline=e.poll('')['cursor'];self.assertEqual(e.sequence,0)
            fresh=turn('turn-2');lines=''.join(json.dumps(x)+'\n' for x in fresh[:-1])
            with file.open('a',encoding='utf8') as f:f.write(lines+json.dumps(fresh[-1]))
            w.scan();self.assertEqual(e.sequence,0)
            with file.open('a',encoding='utf8') as f:f.write('\n')
            w.scan();self.assertEqual(len(e.poll(baseline,0)['events']),1)
            w.scan();self.assertEqual(e.sequence,1)
    def test_inflight_turn_at_startup_completes(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'rollout.jsonl';file.write_text(''.join(json.dumps(x)+'\n' for x in turn()[:-1]),encoding='utf8')
            e=n.Events();w=n.Watcher(d,e);w.scan()
            with file.open('a',encoding='utf8') as f:f.write(json.dumps(turn()[-1])+'\n')
            w.scan();self.assertEqual(e.sequence,1)
    def test_partial_record_at_startup_and_invalid_json_shapes(self):
        with tempfile.TemporaryDirectory() as d:
            seq=turn();file=Path(d)/'rollout.jsonl';tail=json.dumps(seq[-1]);file.write_text(''.join(json.dumps(x)+'\n' for x in seq[:-1])+tail[:30],encoding='utf8')
            e=n.Events();w=n.Watcher(d,e);w.scan()
            with file.open('a',encoding='utf8') as f:f.write(tail[30:]+'\n[]\nnull\n')
            w.scan();self.assertEqual(e.sequence,1)

if __name__=='__main__':unittest.main()
