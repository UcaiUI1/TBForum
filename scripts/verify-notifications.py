"""Windows + WSL isolated fault verification. Requires psycopg[binary], redis, websocket-client, psutil.

Creates a NEW PostgreSQL cluster, Redis directory, application configs and ports. Never uses the
default business database/cache/upload directory. All child processes are stopped in finally.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

import psycopg
import psutil
import redis
import websocket

ROOT = Path(__file__).resolve().parents[1]
HIDDEN = subprocess.CREATE_NO_WINDOW
APPS = []
SOCKETS = []
RESULTS = {}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def wait_for(check, description, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = check()
        if value:
            return value
        time.sleep(.1)
    raise AssertionError('Timed out: ' + description)


def save():
    (OUT / 'results.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding='utf-8')


def recorded(name, details):
    RESULTS[name] = details
    save()
    print('PASS ' + name, flush=True)


def run(args, log):
    with (OUT / log).open('ab') as handle:
        subprocess.run([str(a) for a in args], check=True, stdout=handle, stderr=subprocess.STDOUT,
                       timeout=40, creationflags=HIDDEN)


def db():
    return psycopg.connect(host='127.0.0.1', port=PG_PORT, dbname='notification_verify', user='notification_verify', autocommit=True)


def query(statement, params=()):
    with db() as connection:
        cursor = connection.execute(statement, params)
        return cursor.fetchall() if cursor.description else []


def api(port, path, token='', method='GET', body=None, expected=200):
    request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        response = OPENER.open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, (path, response.status, response.read().decode()[:300])
    content = response.read()
    return json.loads(content) if content else None


def fingerprint_original_files():
    files = []
    for directory in ('data', 'uploads'):
        if (ROOT / directory).exists():
            files.extend(path for path in (ROOT / directory).rglob('*') if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(files)}


def start_redis():
    run(['wsl.exe', '-d', 'Ubuntu', '--', 'redis-server', REDIS_CONFIG_WSL], 'redis-start.log')
    wait_for(lambda: cache.ping(), 'isolated redis ready', 15)
    assert REDIS_CONFIG_WSL in cache.info('server')['config_file']


def stop_redis():
    try:
        assert REDIS_CONFIG_WSL in cache.info('server')['config_file']
        cache.shutdown(save=True)
    except redis.exceptions.ConnectionError:
        pass


def start_app(port, label, workers=True, consumer=True, queue_timeout='60s'):
    config = WORK / (label + '.properties')
    config.write_text('\n'.join([
        'server.address=127.0.0.1', f'server.port={port}',
        f'spring.datasource.url=jdbc:postgresql://127.0.0.1:{PG_PORT}/notification_verify',
        'spring.datasource.username=notification_verify', 'spring.datasource.password=',
        'spring.data.redis.host=127.0.0.1', f'spring.data.redis.port={REDIS_PORT}', 'spring.data.redis.password=',
        'spring.data.redis.timeout=500ms', 'app.redis.enabled=true', 'app.redis.failure-cooldown=1s',
        'app.jwt.secret=' + SECRET, f'app.public-base-url=http://127.0.0.1:{port}',
        'app.storage.local.dir=' + (WORK / 'uploads').as_posix(), 'app.upload.legacy-reconciliation-enabled=false',
        'app.upload.cleanup-interval=24h', 'app.startup.rounds=2',
        f'app.notifications.workers-enabled={str(workers).lower()}',
        f'app.notifications.consumer-enabled={str(consumer).lower()}',
        'app.notifications.poll-interval=100ms', 'app.notifications.lease=3s',
        'app.notifications.queue-timeout=' + queue_timeout,
        'app.notifications.retry-base=1s', 'app.notifications.retry-max=2s', 'app.notifications.max-attempts=4',
        'logging.level.org.hibernate.orm.incubating=ERROR',
    ]), encoding='utf-8')
    with (OUT / (label + '.log')).open('wb') as log:
        app = subprocess.Popen(['java', '-Xms128m', '-Xmx512m', '-jar', str(ARTIFACT), '--spring.profiles.active=prod',
            '--spring.config.additional-location=' + config.as_uri()], cwd=WORK, stdout=log, stderr=subprocess.STDOUT,
            creationflags=HIDDEN)
    APPS.append(app)

    def ready():
        assert app.poll() is None, 'Application exited: ' + label
        try:
            return api(port, '/actuator/health/readiness')['status'] == 'UP'
        except (AssertionError, OSError):
            return False
    wait_for(ready, label + ' readiness', 100)
    print('READY ' + label, flush=True)
    return app


def kill_app(app):
    if app.poll() is None:
        owned = psutil.Process(app.pid)
        assert str(ARTIFACT) in ' '.join(owned.cmdline())
        processes = [process for process in [owned] + owned.children(recursive=True)
                     if process.name().lower() == 'java.exe' and str(ARTIFACT) in ' '.join(process.cmdline())]
        assert processes
        for process in reversed(processes):
            try:
                process.kill()
            except psutil.NoSuchProcess:
                pass
        psutil.wait_procs(processes, timeout=10)
        app.wait(timeout=10)


class Receiver:
    def __init__(self, port, token):
        self.messages = []
        self.socket = websocket.create_connection(f'ws://127.0.0.1:{port}/ws/notifications',
            # Authentication uses a request header; no application subprotocol is negotiated.
            header={'Sec-WebSocket-Protocol': 'bearer.' + token}, origin=f'http://127.0.0.1:{port}', timeout=1,
            http_no_proxy=['localhost', '127.0.0.1'])
        self.closed = False
        self.thread = threading.Thread(target=self.receive, daemon=True)
        self.thread.start()
        SOCKETS.append(self)

    def receive(self):
        while not self.closed:
            try:
                value = self.socket.recv()
                if not value:
                    break
                self.messages.append(json.loads(value))
            except websocket.WebSocketTimeoutException:
                continue
            except (websocket.WebSocketException, OSError, ValueError):
                break

    def ids(self):
        return {item['id'] for item in self.messages if 'id' in item}

    def close(self):
        self.closed = True
        self.socket.close()
        self.thread.join(timeout=2)


def all_sent():
    return query("select count(*) from notification_tasks where status<>'SENT'")[0][0] == 0


def task_for(comment_id):
    return query('select t.id,t.status,t.attempts,n.id from notification_tasks t join notifications n on n.id=t.notification_id where n.comment_id=%s', (comment_id,))[0]


def check_scenarios():
    a = start_app(A_PORT, 'commit-before-crash', workers=False)
    assert query('select max(version::int) from flyway_schema_history where success=true')[0][0] == 9
    accounts = {}
    for name in ('notify_alice', 'notify_bob', 'notify_admin'):
        accounts[name] = api(A_PORT, '/api/auth/register', method='POST', body={'username': name, 'password': 'verify-only-notifications-20261018'}, expected=201)['token']
    alice, bob, admin = (accounts[name] for name in ('notify_alice', 'notify_bob', 'notify_admin'))
    query("update users set role='ADMIN' where username='notify_admin'")
    api(A_PORT, '/api/auth/me/verified', alice, 'PATCH', expected=200)
    api(A_PORT, '/api/forums', alice, 'POST', {'name': 'Reliable notification verification', 'slug': 'notify-verify', 'description': 'isolated fixture'}, 201)
    post = api(A_PORT, '/api/forums/notify-verify/posts', alice, 'POST', {'title': 'Notification verification', 'content': 'isolated fixture'}, 201)

    def comment(port, label):
        return api(port, f'/api/posts/{post["id"]}/comments', bob, 'POST', {'content': label}, 201)['id']

    first = comment(A_PORT, 'committed before worker and process crash')
    assert task_for(first)[1] == 'READY'
    kill_app(a)
    a = start_app(A_PORT, 'recovery-a')
    b = start_app(B_PORT, 'recovery-b')
    wait_for(all_sent, 'committed task recovered after process kill')
    recorded('commit_before_dispatch_process_crash', {'comment_id': first, 'task_state_after_restart': task_for(first)[1], 'notification_count': 1})

    wait_for(lambda: cache.pubsub_numsub('tbforum:ws:events')[0][1] == 2, 'both instances subscribed')
    receiver_a = Receiver(A_PORT, alice)
    receiver_b = Receiver(B_PORT, alice)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        comments = list(pool.map(lambda index: comment(A_PORT if index % 2 == 0 else B_PORT, 'dual ' + str(index)), range(32)))
    wait_for(all_sent, 'dual-instance tasks complete')
    ids = {task_for(value)[3] for value in comments}
    wait_for(lambda: ids <= receiver_a.ids() and ids <= receiver_b.ids(), 'both socket owners receive every event')
    assert all(isinstance(message['createdAt'], str) for message in receiver_a.messages)
    assert query('select count(*) from notifications')[0][0] == 33
    counter, actual = query('select p.comment_count,(select count(*) from comments c where c.post_id=p.id and c.deleted=false) from posts p where p.id=%s', (post['id'],))[0]
    assert counter == actual == 33, (counter, actual)
    recorded('dual_instance_competing_workers_and_broadcast', {'concurrent_comments': 32, 'unique_notifications': len(ids), 'received_on_a': len(ids & receiver_a.ids()), 'received_on_b': len(ids & receiver_b.ids()), 'comment_counter': counter, 'actual_active_comments': actual})

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda index: api(A_PORT if index % 2 == 0 else B_PORT,
            f'/api/posts/{post["id"]}/comments/{comments[0]}', bob, 'DELETE', expected=204), range(16)))
    counter, actual = query('select p.comment_count,(select count(*) from comments c where c.post_id=p.id and c.deleted=false) from posts p where p.id=%s', (post['id'],))[0]
    audits = query("select count(*) from audit_logs where action='DELETE_COMMENT' and target_id=%s", (comments[0],))[0][0]
    assert counter == actual == 32 and audits == 1, (counter, actual, audits)
    recorded('dual_instance_idempotent_comment_delete', {'concurrent_delete_requests': 16, 'comment_counter': counter, 'actual_active_comments': actual, 'delete_audits': audits})

    task_id = task_for(comments[0])[0]
    delivered_before = len(receiver_a.messages) + len(receiver_b.messages)
    for _ in range(10):
        cache.xadd('tbforum:notifications:delivery', {'taskId': task_id})
    wait_for(lambda: cache.xlen('tbforum:notifications:delivery') == 0, 'duplicate stream records acknowledged')
    assert query('select count(*) from notifications')[0][0] == 33
    assert len(receiver_a.messages) + len(receiver_b.messages) == delivered_before
    recorded('duplicate_stream_records', {'duplicates_injected': 10, 'extra_notifications': 0, 'extra_realtime_deliveries': 0})

    stop_redis()
    outage_comment = comment(A_PORT, 'broker offline but business commit succeeds')
    wait_for(lambda: task_for(outage_comment)[1] == 'DEAD', 'bounded retries exhausted', 30)
    dead = task_for(outage_comment)
    assert dead[2] == 4
    failed = api(A_PORT, '/api/admin/notification-tasks', admin)
    assert any(item['id'] == dead[0] for item in failed)
    api(A_PORT, '/api/admin/notification-tasks', alice, expected=403)
    api(A_PORT, f'/api/admin/notification-tasks/{dead[0]}/replay', alice, 'POST', expected=403)
    start_redis()
    wait_for(lambda: cache.pubsub_numsub('tbforum:ws:events')[0][1] == 2, 'listeners recover')
    api(A_PORT, f'/api/admin/notification-tasks/{dead[0]}/replay', admin, 'POST')
    wait_for(all_sent, 'dead task replay after broker recovery')
    api(A_PORT, f'/api/admin/notification-tasks/{dead[0]}/replay', admin, 'POST', expected=409)
    recorded('broker_outage_retry_exhaustion_and_admin_replay', {'business_status': 201, 'attempts_before_replay': dead[2], 'final_state': task_for(outage_comment)[1], 'ordinary_user_access': 403})

    receiver_a.close(); receiver_b.close()
    kill_app(a); kill_app(b)
    a = start_app(A_PORT, 'persistent-queue-producer', consumer=False)
    queued_comment = comment(A_PORT, 'queued message survives redis and application restart')
    wait_for(lambda: task_for(queued_comment)[1] == 'QUEUED', 'task persisted to queue')
    wait_for(lambda: cache.xlen('tbforum:notifications:delivery') >= 1, 'stream record present')
    cache.xreadgroup('notification-dispatch', 'crashed-external-consumer', {'tbforum:notifications:delivery': '>'}, count=100)
    assert cache.xpending('tbforum:notifications:delivery', 'notification-dispatch')['pending'] >= 1
    kill_app(a); stop_redis(); start_redis()
    assert cache.xpending('tbforum:notifications:delivery', 'notification-dispatch')['pending'] >= 1
    b = start_app(B_PORT, 'persistent-queue-recovery')
    wait_for(all_sent, 'orphan pending entry reclaimed')
    wait_for(lambda: cache.xpending('tbforum:notifications:delivery', 'notification-dispatch')['pending'] == 0, 'pending entries acknowledged')
    recorded('durable_stream_restart_and_pending_reclaim', {'final_state': task_for(queued_comment)[1], 'pending_after_recovery': 0})
    kill_app(b)

    # Test-only database trigger pauses exactly between network dispatch and its completion update.
    # Killing the application rolls the completion update back, leaving a real abandoned database lease.
    a = start_app(A_PORT, 'lease-crash-producer', workers=False)
    crash_comment = comment(A_PORT, 'crash after broadcast before completion')
    crash_task = task_for(crash_comment)[0]
    query("""create function verification_pause_sent() returns trigger language plpgsql as $$
        begin if new.status='SENT' then perform pg_sleep(60); end if; return new; end $$""")
    query('create trigger verification_pause_sent before update on notification_tasks for each row execute function verification_pause_sent()')
    kill_app(a)
    a = start_app(A_PORT, 'lease-crash-consumer')
    paused = wait_for(lambda: query("select pid from pg_stat_activity where datname='notification_verify' and wait_event='PgSleep'"), 'consumer paused before completion commit')
    assert task_for(crash_comment)[1] == 'IN_FLIGHT'
    kill_app(a)
    # A disconnected client's statement can still commit on PostgreSQL. Explicitly abort the
    # test-only paused backend to deterministically exercise the uncommitted completion window.
    for (pid,) in paused:
        query("select pg_terminate_backend(pid) from pg_stat_activity where pid=%s and datname='notification_verify'", (pid,))
    wait_for(lambda: not query("select pid from pg_stat_activity where datname='notification_verify' and wait_event='PgSleep'"), 'paused transaction aborted')
    query('drop trigger verification_pause_sent on notification_tasks')
    query('drop function verification_pause_sent()')
    b = start_app(B_PORT, 'abandoned-lease-recovery')
    wait_for(all_sent, 'other instance takes over abandoned delivery lease')
    assert task_for(crash_comment)[2] == 2
    recorded('consumer_process_kill_after_dispatch', {'task_id': crash_task, 'attempts': task_for(crash_comment)[2], 'final_state': 'SENT', 'delivery_semantics': 'at least once; notification identity unchanged'})

    # More than twenty missed notifications remain accessible, and repeated read operations are idempotent.
    for index in range(40):
        comment(B_PORT, 'offline history ' + str(index))
    wait_for(all_sent, 'offline history tasks complete')
    found = []
    before = None
    while True:
        page = api(B_PORT, '/api/notifications/history?size=20' + (f'&before={before}' if before else ''), alice)
        found.extend(item['id'] for item in page['content'])
        if not page['hasMore']:
            break
        before = page['nextBefore']
    expected = query('select count(*) from notifications')[0][0]
    assert len(found) == len(set(found)) == expected
    api(B_PORT, f'/api/notifications/{found[0]}/read', alice, 'PATCH', expected=204)
    api(B_PORT, f'/api/notifications/{found[0]}/read', alice, 'PATCH', expected=204)
    assert api(B_PORT, '/api/notifications/history', alice)['unreadCount'] == expected - 1
    assert api(B_PORT, '/api/notifications/history', bob)['content'] == []
    api(B_PORT, f'/api/notifications/{found[0]}/read', bob, 'PATCH', expected=403)
    recorded('offline_history_pagination_and_idempotent_read', {'notifications': expected, 'unique_history_rows': len(set(found)), 'unread_after_two_identical_read_requests': expected - 1})
    metrics = api(B_PORT, '/actuator/metrics/tbforum.notifications.tasks', admin)
    assert metrics['name'] == 'tbforum.notifications.tasks'
    recorded('observability', {'task_gauge_available': True, 'oldest_age_gauge': api(B_PORT, '/actuator/metrics/tbforum.notifications.oldest.seconds', admin)['name']})
    counter, actual = query('select p.comment_count,(select count(*) from comments c where c.post_id=p.id and c.deleted=false) from posts p where p.id=%s', (post['id'],))[0]
    assert counter == actual == 75, (counter, actual)
    # Exercise the deployed migration statement against a deliberately damaged isolated fixture.
    before_fields = query("select to_jsonb(p)-'comment_count' from posts p where id=%s", (post['id'],))[0][0]
    query('update posts set comment_count=0 where id=%s', (post['id'],))
    query((ROOT / 'src/main/resources/db/migration/V9__reconcile_comment_counts.sql').read_text(encoding='utf-8'))
    repaired = query('select comment_count from posts where id=%s', (post['id'],))[0][0]
    after_fields = query("select to_jsonb(p)-'comment_count' from posts p where id=%s", (post['id'],))[0][0]
    assert repaired == 75 and before_fields == after_fields
    recorded('historical_comment_counter_reconciliation', {'damaged_counter': 0, 'repaired_counter': repaired, 'actual_active_comments': actual, 'other_post_fields_unchanged': True})
    if args.browser_check:
        print('BROWSER_READY http://127.0.0.1:' + str(B_PORT), flush=True)
        (WORK / 'browser-ready').touch()
        end = time.monotonic() + 240
        while time.monotonic() < end and not (WORK / 'browser-done').exists():
            time.sleep(.5)


def main():
    before = fingerprint_original_files()
    started_pg = False
    try:
        for port in (PG_PORT, REDIS_PORT, A_PORT, B_PORT):
            with socket.socket() as probe:
                assert probe.connect_ex(('127.0.0.1', port)) != 0, ('Port already in use', port)
        WORK.mkdir(parents=True, exist_ok=False)
        OUT.mkdir(parents=True, exist_ok=False)
        shutil.copy2(SOURCE_ARTIFACT, ARTIFACT)
        run([PG / 'initdb.exe', '-D', WORK / 'postgres', '-U', 'notification_verify', '--auth-local=trust', '--auth-host=trust', '--encoding=UTF8', '--locale=C'], 'initdb.log')
        with (WORK / 'postgres/postgresql.conf').open('a') as handle:
            handle.write(f"\nlisten_addresses='127.0.0.1'\nport={PG_PORT}\nshared_buffers='64MB'\n")
        run([PG / 'pg_ctl.exe', '-D', WORK / 'postgres', '-l', OUT / 'postgres.log', '-w', 'start'], 'pg-start.log')
        started_pg = True
        with psycopg.connect(host='127.0.0.1', port=PG_PORT, dbname='postgres', user='notification_verify', autocommit=True) as connection:
            connection.execute('create database notification_verify')
        (WORK / 'redis').mkdir()
        redis_dir = '/mnt/' + WORK.drive[0].lower() + '/' + (WORK / 'redis').as_posix()[3:]
        (WORK / 'redis.conf').write_text(f'bind 127.0.0.1\nport {REDIS_PORT}\nprotected-mode yes\nappendonly yes\nappendfsync always\ndir {redis_dir}\ndaemonize yes\npidfile {redis_dir}/redis.pid\nlogfile {redis_dir}/redis.log\n', encoding='utf-8')
        start_redis()
        RESULTS['environment'] = {'postgres_port': PG_PORT, 'redis_port': REDIS_PORT, 'application_ports': [A_PORT, B_PORT], 'artifact_sha256': hashlib.sha256(ARTIFACT.read_bytes()).hexdigest(), 'isolated': True}
        check_scenarios()
        assert fingerprint_original_files() == before
        recorded('original_files_unchanged', {'files_checked': len(before)})
        RESULTS['passed'] = True
    except Exception as error:
        RESULTS['passed'] = False
        RESULTS['failure_type'] = type(error).__name__
        raise
    finally:
        for receiver in SOCKETS:
            receiver.close()
        for app in APPS:
            kill_app(app)
        try:
            stop_redis()
        except (redis.exceptions.RedisError, AssertionError):
            pass
        if started_pg:
            run([PG / 'pg_ctl.exe', '-D', WORK / 'postgres', '-w', '-m', 'immediate', 'stop'], 'pg-stop.log')
        RESULTS['services_stopped'] = all(app.poll() is not None for app in APPS)
        if OUT.exists():
            save()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--postgres-bin', default=r'C:\Program Files\PostgreSQL\18\bin')
    parser.add_argument('--run-id', default=time.strftime('%Y%m%d-%H%M%S'))
    parser.add_argument('--browser-check', action='store_true')
    args = parser.parse_args()
    assert args.run_id.replace('-', '').isalnum()
    PG = Path(args.postgres_bin)
    PG_PORT, REDIS_PORT, A_PORT, B_PORT = 35432, 36379, 38089, 38090
    WORK = ROOT / 'work' / ('notification-' + args.run_id)
    OUT = ROOT / 'outputs' / ('notification-' + args.run_id)
    SOURCE_ARTIFACT = ROOT / 'target/tbforum-0.1.0.jar'
    ARTIFACT = OUT / 'candidate.jar'
    SECRET = secrets.token_hex(32)
    REDIS_CONFIG_WSL = '/mnt/' + WORK.drive[0].lower() + '/' + (WORK / 'redis.conf').as_posix()[3:]
    cache = redis.Redis(host='127.0.0.1', port=REDIS_PORT, decode_responses=True, socket_timeout=2)
    main()
