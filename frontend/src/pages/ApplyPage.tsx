import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import type { Application, Form } from '../types';
import { countWords, gradeList } from '../grades';
import { ErrorBox, GradeSelect, StatusBadge } from '../components/Common';

export default function ApplyPage() {
  const { teamId } = useParams();
  const { user } = useAuth();
  const [form, setForm] = useState<Form | null>(null);
  const [app, setApp] = useState<Application | null>(null);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [grade, setGrade] = useState<number | null>(user?.grade ?? null);
  const [parentEmail, setParentEmail] = useState(user?.parentEmail ?? '');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Form>(`/api/teams/${teamId}/form`).then(setForm).catch(e => setError(e.message));
    api<Application>(`/api/applications/team/${teamId}`).then(a => {
      setApp(a);
      setAnswers(Object.fromEntries(a.answers.map(x => [x.questionId, x.response])));
      if (a.grade != null) setGrade(a.grade);
      if (a.parentEmail) setParentEmail(a.parentEmail);
    }).catch(e => { if (!(e instanceof ApiError && e.status === 404)) setError(e.message); });
  }, [teamId]);

  if (!form) return <div className="container narrow">{error ? <ErrorBox error={error} /> : <p className="muted">Loading…</p>}</div>;

  const locked = app != null && app.status !== 'DRAFT' && app.status !== 'WITHDRAWN';
  const overLimit = form.questions.some(q => q.wordLimit != null && countWords(answers[q.id!] ?? '') > q.wordLimit);
  const gradeOk = grade == null || form.eligibleGrades.length === 0 || form.eligibleGrades.includes(grade);

  async function save(submit: boolean) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const a = await api<Application>(`/api/applications/team/${teamId}`, {
        method: 'PUT',
        body: {
          grade, parentEmail: parentEmail || null, submit,
          answers: form!.questions.map(q => ({ questionId: q.id, response: answers[q.id!] ?? '' })),
        },
      });
      setApp(a);
      setNotice(submit ? 'Application submitted. You can track it under "My applications".' : 'Draft saved.');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (e) {
      setError((e as Error).message);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally { setBusy(false); }
  }

  return (
    <div className="container narrow">
      <p><Link to={`/teams/${teamId}`}>← {form.teamName}</Link></p>
      <div className="card">
        <div className="team-card-head">
          <h1>{form.title}</h1>
          {app && <StatusBadge status={app.status} />}
        </div>
        <p className="muted small">{gradeList(form.eligibleGrades)}
          {form.deadline && <> · Deadline {new Date(form.deadline + 'T00:00').toLocaleDateString()}</>}</p>
        {form.intro && <p className="pre">{form.intro}</p>}
        <ErrorBox error={error} />
        {notice && <div className="alert alert-ok">{notice}</div>}
        {!form.acceptingApplications && !locked && <div className="alert alert-error">This team isn't accepting applications right now.</div>}

        {locked ? (
          <>
            <p>You submitted this application{app!.submittedAt && <> on {new Date(app!.submittedAt).toLocaleDateString()}</>}.</p>
            {app!.answers.map(a => (
              <div key={a.questionId} className="answer-view"><h4>{a.prompt}</h4><p className="pre">{a.response || <em className="muted">(no answer)</em>}</p></div>
            ))}
            <Link to="/my/applications" className="btn btn-secondary">Back to my applications</Link>
          </>
        ) : (
          <form onSubmit={e => { e.preventDefault(); save(true); }}>
            <div className="two-col">
              <label className="field"><span>Your grade *</span>
                <GradeSelect value={grade} onChange={setGrade} placeholder="Choose…" /></label>
              <label className="field"><span>Parent/guardian email *</span>
                <input type="email" value={parentEmail} onChange={e => setParentEmail(e.target.value)} /></label>
            </div>
            {!gradeOk && <p className="field-error">This team is only accepting {gradeList(form.eligibleGrades).toLowerCase()}.</p>}

            {form.questions.map((q, i) => {
              const val = answers[q.id!] ?? '';
              const words = countWords(val);
              const over = q.wordLimit != null && words > q.wordLimit;
              const set = (v: string) => setAnswers(a => ({ ...a, [q.id!]: v }));
              return (
                <div className="field question" key={q.id}>
                  <span className="q-label">{i + 1}. {q.prompt}{q.required && ' *'}</span>
                  {q.helpText && <span className="muted small">{q.helpText}</span>}
                  {q.type === 'MULTIPLE_CHOICE' ? (
                    <div className="choices">
                      {(q.options ?? '').split('\n').filter(Boolean).map(opt => (
                        <label key={opt} className="check">
                          <input type="radio" name={`q${q.id}`} checked={val === opt} onChange={() => set(opt)} /> {opt}
                        </label>
                      ))}
                    </div>
                  ) : q.type === 'SHORT_TEXT' ? (
                    <input value={val} onChange={e => set(e.target.value)} />
                  ) : (
                    <textarea rows={6} value={val} onChange={e => set(e.target.value)} />
                  )}
                  {q.type !== 'MULTIPLE_CHOICE' && (
                    <span className={`word-count ${over ? 'over' : ''}`}>
                      {words}{q.wordLimit != null && ` / ${q.wordLimit}`} words{over && ' (over the limit!)'}
                    </span>
                  )}
                </div>
              );
            })}

            <div className="card-actions sticky-actions">
              <button type="button" className="btn btn-secondary" disabled={busy || overLimit || !form.acceptingApplications}
                onClick={() => save(false)}>Save draft</button>
              <button className="btn btn-primary" disabled={busy || overLimit || !gradeOk || !form.acceptingApplications}>Submit application</button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
