package com.mysite.sbb.util;

public class PasswordPolicy {

    // 같은 문자 3연속
    private static boolean hasRepeat3(String s) {
        return s != null && s.matches(".*(.)\\1{2,}.*");
    }

    // 알파벳/숫자 연속/역연속(기본 4자 이상)
    private static boolean hasSequentialRun(String s, int minLen) {
        if (s == null || s.length() < minLen) return false;
        int cnt = 1;
        for (int i = 1; i < s.length(); i++) {
            char a = Character.toLowerCase(s.charAt(i - 1));
            char b = Character.toLowerCase(s.charAt(i));
            boolean bothAlpha = Character.isLetter(a) && Character.isLetter(b);
            boolean bothDigit = Character.isDigit(a) && Character.isDigit(b);
            if (bothAlpha || bothDigit) {
                int diff = b - a;
                if (diff == 1 || diff == -1) cnt++;
                else cnt = 1;
                if (cnt >= minLen) return true;
            } else cnt = 1;
        }
        return false;
    }

    // 키보드 행 기반(정/역방향) – 기본 4자
    private static final String[] ROWS = {
            "`1234567890-=",
            "qwertyuiop[]\\",
            "asdfghjkl;\'",
            "zxcvbnm,./"
    };
    private static boolean hasKeyboardRowSeq(String s, int minLen) {
        if (s == null || s.length() < minLen) return false;
        String low = s.toLowerCase();
        for (String row : ROWS) {
            String r = row.toLowerCase();
            String rev = new StringBuilder(r).reverse().toString();
            for (int L = minLen; L <= r.length(); L++) {
                for (int i = 0; i + L <= r.length(); i++) {
                    String seq = r.substring(i, i + L);
                    String rseq = rev.substring(rev.length() - i - L, rev.length() - i);
                    if (low.contains(seq) || low.contains(rseq)) return true;
                }
            }
        }
        return false;
    }

    // 아이디/이메일 로컬파트 포함 금지
    private static boolean containsUserInfo(String pw, String username, String email) {
        if (pw == null) return false;
        String low = pw.toLowerCase();
        if (username != null && !username.isBlank() && low.contains(username.toLowerCase())) return true;
        if (email != null) {
            int at = email.indexOf('@');
            if (at > 0) {
                String local = email.substring(0, at).toLowerCase();
                if (!local.isBlank() && low.contains(local)) return true;
            }
        }
        return false;
    }

    public static void validate(String password, String username, String email) {
        if (password == null || password.length() < 8)
            throw new IllegalArgumentException("비밀번호는 최소 8자 이상이어야 합니다.");
        if (password.length() > 64)
            throw new IllegalArgumentException("비밀번호가 너무 깁니다(최대 64자).");
        if (hasRepeat3(password))
            throw new IllegalArgumentException("같은 문자를 3번 이상 연속으로 사용할 수 없습니다.");
        if (hasSequentialRun(password, 4) || hasKeyboardRowSeq(password, 4))
            throw new IllegalArgumentException("연속/역연속/키보드 패턴은 사용할 수 없습니다.");
        if (containsUserInfo(password, username, email))
            throw new IllegalArgumentException("아이디/이메일 일부를 포함할 수 없습니다.");
    }
}