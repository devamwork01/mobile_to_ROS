"""app.spawn: fire-and-forget tasks are kept alive until done and their failures are logged."""

import asyncio
import gc

from sensorstream import app


def test_spawned_task_runs_to_completion_without_a_caller_reference():
    done = []

    async def work():
        await asyncio.sleep(0.01)
        gc.collect()                      # the event loop only holds tasks weakly
        done.append(True)

    async def run():
        app.spawn(work(), "work")
        assert app._background, "the task must be referenced while it runs"
        await asyncio.sleep(0.05)
        assert not app._background, "and released once done"

    asyncio.run(run())
    assert done == [True]


def test_spawned_task_failure_is_logged(capsys):
    async def boom():
        raise RuntimeError("phone vanished")

    async def run():
        app.spawn(boom(), "send filters")
        await asyncio.sleep(0.01)

    asyncio.run(run())
    out = capsys.readouterr().out
    assert "send filters" in out and "phone vanished" in out
