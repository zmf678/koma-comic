using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace KomaComic.Windows
{
    internal sealed class TranslationRequestPriority
    {
        private int _background;

        public TranslationRequestPriority(bool background) { _background = background ? 1 : 0; }
        public bool IsBackground { get { return Volatile.Read(ref _background) != 0; } }
        public void Promote() { Volatile.Write(ref _background, 0); }
    }

    internal sealed class TranslationRequestQueue
    {
        private sealed class Waiter
        {
            public TranslationRequestPriority Priority;
            public LinkedListNode<Waiter> Node;
            public readonly TaskCompletionSource<bool> Ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        }

        private readonly LinkedList<Waiter> _waiting = new LinkedList<Waiter>();
        private bool _running;

        public Task WaitAsync(TranslationRequestPriority priority, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Waiter entry;
            lock (_waiting)
            {
                if (!_running)
                {
                    _running = true;
                    return Task.FromResult(true);
                }
                entry = new Waiter { Priority = priority };
                entry.Node = _waiting.AddLast(entry);
            }
            return WaitForTurnAsync(entry, token);
        }

        private async Task WaitForTurnAsync(Waiter entry, CancellationToken token)
        {
            using (token.Register(() => Cancel(entry)))
                await entry.Ready.Task.ConfigureAwait(false);
        }

        private void Cancel(Waiter entry)
        {
            lock (_waiting)
            {
                if (entry.Node.List == null) return;
                _waiting.Remove(entry.Node);
            }
            entry.Ready.TrySetCanceled();
        }

        public void Release()
        {
            Waiter entry;
            lock (_waiting)
            {
                if (_waiting.Count == 0)
                {
                    _running = false;
                    return;
                }
                var next = _waiting.First;
                while (next != null && next.Value.Priority != null && next.Value.Priority.IsBackground)
                    next = next.Next;
                next = next ?? _waiting.First;
                entry = next.Value;
                _waiting.Remove(next);
            }
            entry.Ready.SetResult(true);
        }
    }
}
