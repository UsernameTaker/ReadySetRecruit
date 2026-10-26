import type { ApplicationStatus } from '../types';
import { GRADES, gradeLabel } from '../grades';

export function ErrorBox({ error }: { error: string | null }) {
  return error ? <div className="alert alert-error" role="alert">{error}</div> : null;
}

const STATUS_STYLE: Record<ApplicationStatus, string> = {
  DRAFT: 'badge-gray', SUBMITTED: 'badge-blue', UNDER_REVIEW: 'badge-amber', ACCEPTED: 'badge-green',
  WAITLISTED: 'badge-amber', DECLINED: 'badge-red', WITHDRAWN: 'badge-gray',
};

export function StatusBadge({ status }: { status: ApplicationStatus }) {
  return <span className={`badge ${STATUS_STYLE[status]}`}>{status.replace('_', ' ').toLowerCase()}</span>;
}

export function GradeSelect({ value, onChange, placeholder = 'Any grade', id }: {
  value: number | null; onChange: (g: number | null) => void; placeholder?: string; id?: string;
}) {
  return (
    <select id={id} value={value ?? ''} onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))}>
      <option value="">{placeholder}</option>
      {GRADES.map(g => <option key={g} value={g}>{g === 0 ? 'Kindergarten' : `Grade ${g}`}</option>)}
    </select>
  );
}

export function GradePicker({ value, onChange }: { value: number[]; onChange: (g: number[]) => void }) {
  const toggle = (g: number) => onChange(value.includes(g) ? value.filter(x => x !== g) : [...value, g].sort((a, b) => a - b));
  const preset = (from: number, to: number) => onChange(GRADES.filter(g => g >= from && g <= to));
  return (
    <div>
      <div className="grade-picker">
        {GRADES.map(g => (
          <button type="button" key={g} className={`grade-btn ${value.includes(g) ? 'on' : ''}`} onClick={() => toggle(g)}
            aria-pressed={value.includes(g)}>{gradeLabel(g)}</button>
        ))}
      </div>
      <div className="small muted presets">
        Quick pick: <button type="button" className="link" onClick={() => preset(0, 5)}>Elementary</button>
        {' · '}<button type="button" className="link" onClick={() => preset(6, 8)}>Middle</button>
        {' · '}<button type="button" className="link" onClick={() => preset(9, 12)}>High</button>
        {' · '}<button type="button" className="link" onClick={() => onChange([])}>All grades</button>
      </div>
    </div>
  );
}
