import json
import time

from laura_voice.link import Outbox, Task, TaskQueue, dumps, persona_stamp, read_persona, task_prompt


def test_queue_runs_one_at_a_time():
    q = TaskQueue()
    q.add(Task("a", "first"))
    q.add(Task("b", "second"))
    assert q.next().task_id == "a"
    assert q.next() is None
    assert q.finish("done").state == "done"
    assert q.next().task_id == "b"


def test_cancel_only_pending():
    q = TaskQueue()
    q.add(Task("a", "x"))
    q.add(Task("b", "y"))
    q.next()
    assert q.cancel("a") is None
    assert q.cancel("b").state == "cancelled"
    assert not q.pending


def test_outbox_bounded_and_expiring():
    box = Outbox(maxlen=3, ttl=60)
    for i in range(5):
        box.put(str(i))
    box.items[0] = (time.time() - 120, box.items[0][1])
    assert box.drain() == ["3", "4"]
    assert box.drain() == []


def test_task_prompt_carries_context():
    ctx = [{"role": "user", "content": "is the server ok?"}, {"role": "assistant", "content": "Let me check."}]
    p = task_prompt("check disk usage on the server", ctx, "en")
    assert "Hector: is the server ok?" in p
    assert "Laura (voice): Let me check." in p
    assert p.rstrip().endswith("check disk usage on the server")


def test_persona_files(tmp_path):
    (tmp_path / "memories").mkdir()
    (tmp_path / "SOUL.md").write_text("# Laura\n" + "x" * 10000)
    (tmp_path / "memories" / "USER.md").write_text("Hector")
    p = read_persona(tmp_path)
    assert p["soul"].startswith("# Laura") and len(p["soul"]) == 6000
    assert p["user"] == "Hector" and p["memory"] == ""
    assert persona_stamp(tmp_path)[2] == 0


def test_dumps_keeps_unicode():
    assert json.loads(dumps(type="message", text="알겠어요"))["text"] == "알겠어요"
    assert "알겠어요" in dumps(text="알겠어요")
