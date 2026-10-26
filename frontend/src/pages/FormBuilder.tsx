import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { Form, Question, QuestionType } from '../types';
import { ErrorBox, GradePicker } from '../components/Common';

// react needs a key for each question so moving/deleting them doesn't mess up the inputs
type Draft = Question & { key: string };

const TYPE_LABEL: Record<QuestionType, string> = {
  LONG_TEXT: 'Paragraph', SHORT_TEXT: 'Short answer', MULTIPLE_CHOICE: 'Multiple choice',
};

const SUGGESTED = [
  { prompt: 'Why do you want to join our team?', type: 'LONG_TEXT' as QuestionType, wordLimit: 200 },
  { prompt: 'Which areas interest you?', type: 'MULTIPLE_CHOICE' as QuestionType, options: 'Building\nProgramming\nCAD / design\nOutreach / business' },
  { prompt: 'Tell us about a project or problem you worked on.', type: 'LONG_TEXT' as QuestionType, wordLimit: 250 },
  { prompt: 'What school do you attend?', type: 'SHORT_TEXT' as QuestionType, wordLimit: 15 },
];

export default function FormBuilder() {
  const { teamId } = useParams();
  const [form, setForm] = useState<Form | null>(null);
  const [questions, setQuestions] = useState<Draft[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const nextKey = useRef(0);
  const newKey = () => `k${nextKey.current++}`;

  useEffect(() => {
    api<Form>(`/api/my/teams/${teamId}/form`).then(f => {
      setForm(f);
      setQuestions(f.questions.map(q => ({ ...q, key: newKey() })));
    }).catch(e => setError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamId]);

  if (!form) return <div className="container narrow">{error ? <ErrorBox error={error} /> : <p className="muted">Loading…</p>}</div>;

  const setF = <K extends keyof Form>(k: K, v: Form[K]) => setForm(f => f && { ...f, [k]: v });
  const update = (key: string, patch: Partial<Question>) =>
    setQuestions(qs => qs.map(q => (q.key === key ? { ...q, ...patch } : q)));
  const add = (base: Partial<Question> = {}) => setQuestions(qs => [...qs, {
    key: newKey(), id: null, prompt: '', helpText: null, type: 'LONG_TEXT', required: true, wordLimit: 150, options: null, ...base,
  }]);
  const remove = (key: string) => setQuestions(qs => qs.filter(q => q.key !== key));
  const move = (i: number, dir: -1 | 1) => setQuestions(qs => {
    const j = i + dir;
    if (j < 0 || j >= qs.length) return qs;
    const copy = [...qs];
    [copy[i], copy[j]] = [copy[j], copy[i]];
    return copy;
  });

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const f = await api<Form>(`/api/my/teams/${teamId}/form`, {
        method: 'PUT',
        body: {
          title: form!.title, intro: form!.intro, open: form!.open, deadline: form!.deadline || null,
          eligibleGrades: form!.eligibleGrades,
          questions: questions.map(q => { const { key, ...rest } = q; void key; return rest; }),
        },
      });
      setForm(f);
      setQuestions(f.questions.map(q => ({ ...q, key: newKey() })));
      setSaved(f.open ? 'Saved! Your application is live.' : 'Saved. Turn on "Accepting applications" when you\'re ready.');
    } catch (e) { setError((e as Error).message); } finally {
      setBusy(false);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  return (
    <div className="container narrow">
      <p><Link to="/manage">← My teams</Link></p>
      <div className="card">
        <h1>Application builder</h1>
        <p className="muted">{form.teamName}</p>
        <ErrorBox error={error} />
        {saved && <div className="alert alert-ok">{saved} <Link to={`/teams/${teamId}`}>View public page →</Link></div>}

        <label className="field"><span>Title</span><input value={form.title} onChange={e => setF('title', e.target.value)} /></label>
        <label className="field"><span>Intro / instructions</span>
          <textarea rows={3} value={form.intro ?? ''} onChange={e => setF('intro', e.target.value)}
            placeholder="What should students know before applying? Tryout dates, commitment, etc." /></label>
        <div className="field"><span>Which grades can apply?</span>
          <GradePicker value={form.eligibleGrades} onChange={g => setF('eligibleGrades', g)} /></div>
        <div className="two-col">
          <label className="field"><span>Deadline <em className="muted">(optional)</em></span>
            <input type="date" value={form.deadline ?? ''} onChange={e => setF('deadline', e.target.value || null)} /></label>
          <label className="check toggle-open">
            <input type="checkbox" checked={form.open} onChange={e => setF('open', e.target.checked)} />
            <strong>Accepting applications</strong>
          </label>
        </div>
      </div>

      <h2>Questions</h2>
      {questions.length === 0 && <p className="muted">No questions yet. Add your own or start from a suggestion below.</p>}
      {questions.map((q, i) => (
        <div className="card question-editor" key={q.key}>
          <div className="qe-head">
            <strong>Question {i + 1}</strong>
            <div className="qe-tools">
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => move(i, -1)} disabled={i === 0} aria-label="Move up">↑</button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => move(i, 1)} disabled={i === questions.length - 1} aria-label="Move down">↓</button>
              <button type="button" className="btn btn-ghost btn-sm danger" onClick={() => remove(q.key)}>Delete</button>
            </div>
          </div>
          <label className="field"><span>Question</span>
            <input value={q.prompt} onChange={e => update(q.key, { prompt: e.target.value })} placeholder="Type your question" /></label>
          <label className="field"><span>Help text <em className="muted">(optional)</em></span>
            <input value={q.helpText ?? ''} onChange={e => update(q.key, { helpText: e.target.value || null })} /></label>
          <div className="three-col">
            <label className="field"><span>Answer type</span>
              <select value={q.type} onChange={e => {
                const type = e.target.value as QuestionType;
                update(q.key, { type, wordLimit: type === 'MULTIPLE_CHOICE' ? null : q.wordLimit, options: type === 'MULTIPLE_CHOICE' ? (q.options ?? 'Option 1\nOption 2') : null });
              }}>
                {(Object.keys(TYPE_LABEL) as QuestionType[]).map(t => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
              </select>
            </label>
            {q.type !== 'MULTIPLE_CHOICE' && (
              <label className="field"><span>Word limit</span>
                <input type="number" min={1} max={5000} value={q.wordLimit ?? ''} placeholder="No limit"
                  onChange={e => update(q.key, { wordLimit: e.target.value ? Number(e.target.value) : null })} /></label>
            )}
            <label className="check"><input type="checkbox" checked={q.required} onChange={e => update(q.key, { required: e.target.checked })} /> Required</label>
          </div>
          {q.type === 'MULTIPLE_CHOICE' && (
            <label className="field"><span>Options (one per line)</span>
              <textarea rows={4} value={q.options ?? ''} onChange={e => update(q.key, { options: e.target.value })} /></label>
          )}
        </div>
      ))}

      <div className="card subtle">
        <button type="button" className="btn btn-secondary" onClick={() => add()}>+ Add a question</button>
        <p className="small muted" style={{ marginTop: 12 }}>Suggestions:</p>
        <div className="chips">
          {SUGGESTED.map(s => (
            <button key={s.prompt} type="button" className="chip chip-toggle" onClick={() => add({ ...s, options: s.options ?? null, wordLimit: s.wordLimit ?? null })}>+ {s.prompt}</button>
          ))}
        </div>
      </div>

      <div className="card-actions sticky-actions">
        <button className="btn btn-primary btn-lg" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save application'}</button>
      </div>
    </div>
  );
}
