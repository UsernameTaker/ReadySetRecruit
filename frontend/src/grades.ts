export const GRADES = Array.from({ length: 13 }, (_, i) => i); // 0 = K

export const gradeLabel = (g: number) => (g === 0 ? 'K' : String(g));

export function gradeRange(min: number | null, max: number | null): string | null {
  if (min == null && max == null) return null;
  if (min != null && max != null) return min === max ? `Grade ${gradeLabel(min)}` : `Grades ${gradeLabel(min)}–${gradeLabel(max)}`;
  if (min != null) return `Grades ${gradeLabel(min)}+`;
  return `Up to grade ${gradeLabel(max!)}`;
}

export function gradeList(grades: number[]): string {
  if (grades.length === 0) return 'All grades';
  const s = [...grades].sort((a, b) => a - b);
  // collapse consecutive runs: 6,7,8,10 -> 6–8, 10
  const parts: string[] = [];
  let start = s[0], prev = s[0];
  for (const g of [...s.slice(1), Infinity]) {
    if (g === prev + 1) { prev = g; continue; }
    parts.push(start === prev ? gradeLabel(start) : `${gradeLabel(start)}–${gradeLabel(prev)}`);
    start = prev = g;
  }
  return 'Grades ' + parts.join(', ');
}

// has to match TextUtil.wordCount on the backend or the limits won't agree
export function countWords(s: string): number {
  const t = s.trim();
  return t ? t.split(/\s+/).length : 0;
}
