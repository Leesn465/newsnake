package com.mysite.sbb.comment.Reaction;

public record ReactionDelta(long likeDelta, long dislikeDelta) {
    static ReactionDelta none() { return new ReactionDelta(0, 0); }

    static ReactionDelta created(ReactionType t) {
        return (t == ReactionType.LIKE) ? new ReactionDelta(1, 0) : new ReactionDelta(0, 1);
    }
    static ReactionDelta deleted(ReactionType t) {
        return (t == ReactionType.LIKE) ? new ReactionDelta(-1, 0) : new ReactionDelta(0, -1);
    }
    static ReactionDelta changed(ReactionType from, ReactionType to) {
        long like = 0, dislike = 0;
        if (from == ReactionType.LIKE) like -= 1; else dislike -= 1;
        if (to == ReactionType.LIKE) like += 1; else dislike += 1;
        return new ReactionDelta(like, dislike);
    }
}
