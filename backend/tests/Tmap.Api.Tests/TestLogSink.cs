using Serilog.Core;
using Serilog.Events;

namespace Tmap.Api.Tests;

/// <summary>
/// Thread-safe in-memory Serilog sink for asserting structured log output in integration tests.
/// </summary>
public static class TestLogSink
{
    private static readonly List<string> _messages = new();
    private static readonly object _gate = new();

    public static IReadOnlyList<string> Messages
    {
        get
        {
            lock (_gate)
            {
                return _messages.ToList();
            }
        }
    }

    public static void Clear()
    {
        lock (_gate)
        {
            _messages.Clear();
        }
    }

    public static void Add(string rendered)
    {
        lock (_gate)
        {
            _messages.Add(rendered);
        }
    }
}

/// <summary>
/// Minimal <see cref="ILogEventSink"/> that forwards each rendered log event to a delegate,
/// including all structured property values so token-leak assertions can inspect them.
/// </summary>
internal sealed class DelegatingSink(Action<LogEvent> write) : ILogEventSink
{
    public void Emit(LogEvent logEvent) => write(logEvent);
}
