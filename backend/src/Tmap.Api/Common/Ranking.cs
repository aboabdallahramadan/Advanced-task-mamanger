namespace Tmap.Api.Common;

/// <summary>
/// Shared lexicographic rank generator used by every create/reorder handler (Tasks, Projects,
/// NoteGroups, Notes). Uses a midpoint-style append scheme: keys sort as plain strings, and
/// RankAfter always produces a key that sorts after the previous key.
/// </summary>
public static class Ranking
{
    /// <summary>
    /// Returns a rank string that sorts after <paramref name="prev"/>.
    /// If <paramref name="prev"/> is null or empty, returns the mid-alphabet start point "n".
    /// </summary>
    public static string RankAfter(string? prev)
    {
        if (string.IsNullOrEmpty(prev)) return "n"; // mid of 'a'..'z'
        var last = prev[^1];
        if (last < 'z') return prev[..^1] + (char)(last + 1);
        return prev + "n";
    }

    /// <summary>
    /// Returns a rank string that sorts between <paramref name="prev"/> and <paramref name="next"/>.
    /// When <paramref name="next"/> is null or empty, falls back to <see cref="RankAfter"/>.
    /// </summary>
    public static string RankBetween(string? prev, string? next)
    {
        if (string.IsNullOrEmpty(next)) return RankAfter(prev);

        // Find a string that lexicographically sits between prev and next.
        // Simple approach: if prev is a prefix of next or they share a common prefix,
        // extend the shorter one; otherwise use midpoint of last differing chars.
        var p = prev ?? string.Empty;

        // Pad prev to the same length as next with 'a' (lowest visible char).
        var padded = p.PadRight(next.Length, 'a');

        // Walk from the right to find a character we can insert between.
        for (var i = next.Length - 1; i >= 0; i--)
        {
            var pChar = i < padded.Length ? padded[i] : 'a';
            var nChar = next[i];
            if (nChar - pChar > 1)
            {
                var mid = (char)((pChar + nChar) / 2);
                return padded[..i] + mid;
            }
        }

        // No gap found — append 'n' after prev to create a new level.
        return RankAfter(prev);
    }
}
