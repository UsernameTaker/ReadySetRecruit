import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import type { TeamDetail } from '../types';
import { gradeList, gradeRange } from '../grades';
import { ErrorBox } from '../components/Common';

export default function TeamPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const nav = useNavigate();
  const [team, setTeam] = useState<TeamDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [claimMsg, setClaimMsg] = useState('');
  const [claimState, setClaimState] = useState<'idle' | 'open' | 'sent'>('idle');

  useEffect(() => { api<TeamDetail>(`/api/teams/${id}`).then(setTeam).catch(e => setError(e.message)); }, [id]);

  if (error) return <div className="container"><ErrorBox error={error} /></div>;
  if (!team) return <div className="container"><p className="muted">Loading…</p></div>;

  const place = [team.city, team.state, team.zip].filter(Boolean).join(', ');
  const grades = gradeRange(team.minGrade, team.maxGrade);
  const form = team.form;

  async function claim() {
    try {
      await api(`/api/teams/${team!.id}/claim`, { method: 'POST', body: { message: claimMsg } });
      setClaimState('sent');
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="container narrow">
      <p><button className="link" onClick={() => nav(-1)}>← Back</button></p>
      <div className="card">
        <div className="team-card-head">
          <div>
            <h1>{team.name}</h1>
            <p className="muted">
              {team.teamNumber && <>#{team.teamNumber} · </>}
              {team.type === 'COMMUNITY' ? 'Community team' : 'School team'}
              {team.schoolName && <> · {team.schoolName}</>}
              {place && <> · {place}</>}
            </p>
          </div>
          {team.recruiting && <span className="badge badge-blue">Recruiting</span>}
        </div>
        <div className="chips">
          {team.programs.map(p => <span key={p} className="chip">{p}</span>)}
          {grades && <span className="chip chip-outline">{grades}</span>}
        </div>

        {team.description && <p className="pre">{team.description}</p>}
        {!team.description && team.websiteDescription && (
          <blockquote className="from-site">“{team.websiteDescription}” <span className="muted small">— from their website</span></blockquote>
        )}

        <dl className="facts">
          {team.meetingInfo && <><dt>Meetings</dt><dd>{team.meetingInfo}</dd></>}
          {team.costInfo && <><dt>Cost</dt><dd>{team.costInfo}</dd></>}
          {team.contactEmail && <><dt>Contact</dt><dd><a href={`mailto:${team.contactEmail}`}>{team.contactEmail}</a></dd></>}
          {team.websiteUrl && (
            <><dt>Website</dt><dd>
              <a href={team.websiteUrl} target="_blank" rel="noopener noreferrer">{team.websiteTitle || team.websiteUrl}</a>
              {team.websiteAutoFound && <span className="muted small"> (found automatically — not confirmed by the team)</span>}
            </dd></>
          )}
        </dl>
      </div>

      <div className="card apply-box">
        {form?.acceptingApplications ? (
          <>
            <h2>{form.title}</h2>
            <p className="muted">
              {gradeList(form.eligibleGrades)} · {form.questionCount} question{form.questionCount === 1 ? '' : 's'}
              {form.deadline && <> · Deadline {new Date(form.deadline + 'T00:00').toLocaleDateString()}</>}
            </p>
            {!user && <Link className="btn btn-primary" to={`/login?next=/apply/${team.id}`}>Log in to apply</Link>}
            {user?.role === 'STUDENT' && <Link className="btn btn-primary" to={`/apply/${team.id}`}>Start / continue application</Link>}
            {user && user.role !== 'STUDENT' && <p className="muted small">Log in with a student account to apply.</p>}
          </>
        ) : team.joinUrl ? (
          <>
            <h2>How to join</h2>
            <p>This team handles sign-ups on their own site.</p>
            <a className="btn btn-primary" href={team.joinUrl} target="_blank" rel="noopener noreferrer">Go to their join page</a>
          </>
        ) : team.websiteUrl ? (
          <>
            <h2>Interested?</h2>
            <p>This team isn't taking applications on ReadySetRecruit. Check their website for how to join.</p>
            <a className="btn btn-primary" href={team.websiteUrl} target="_blank" rel="noopener noreferrer">Visit website</a>
          </>
        ) : (
          <>
            <h2>Interested?</h2>
            <p>This team isn't taking applications right now{team.contactEmail ? ' — try emailing them.' : '.'}</p>
          </>
        )}
      </div>

      {!team.claimed && (
        <div className="card subtle">
          <h3>Is this your team?</h3>
          {claimState === 'sent' ? <p>Thanks! We'll review your request and email you.</p> : !user || user.role === 'STUDENT' ? (
            <p className="small">Create a <Link to="/register?role=TEAM">team account</Link> to claim this listing, update its info, and take applications.</p>
          ) : claimState === 'open' ? (
            <>
              <textarea rows={3} placeholder="Tell us your role on the team and how we can verify it (e.g. an email on the team's website)."
                value={claimMsg} onChange={e => setClaimMsg(e.target.value)} />
              <button className="btn btn-primary" onClick={claim}>Send claim request</button>
            </>
          ) : (
            <button className="btn btn-secondary" onClick={() => setClaimState('open')}>Claim this team</button>
          )}
        </div>
      )}
    </div>
  );
}
