import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import type { TeamDetail, TeamUpsert } from '../types';
import { ErrorBox, GradeSelect } from '../components/Common';

const EMPTY: TeamUpsert = {
  name: '', teamNumber: '', type: 'COMMUNITY', programs: [], description: '', schoolName: '', city: '', state: '',
  zip: '', websiteUrl: '', contactEmail: '', meetingInfo: '', costInfo: '', minGrade: null, maxGrade: null, recruiting: true,
};

export default function TeamEditor() {
  const { teamId } = useParams();
  const nav = useNavigate();
  const [t, setT] = useState<TeamUpsert>(EMPTY);
  const [programs, setPrograms] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { api<string[]>('/api/meta/programs').then(setPrograms).catch(() => {}); }, []);
  useEffect(() => {
    if (!teamId) return;
    api<TeamDetail>(`/api/teams/${teamId}`).then(d => setT({
      name: d.name, teamNumber: d.teamNumber ?? '', type: d.type, programs: d.programs, description: d.description ?? '',
      schoolName: d.schoolName ?? '', city: d.city ?? '', state: d.state ?? '', zip: d.zip ?? '',
      websiteUrl: d.websiteUrl ?? '', contactEmail: d.contactEmail ?? '', meetingInfo: d.meetingInfo ?? '',
      costInfo: d.costInfo ?? '', minGrade: d.minGrade, maxGrade: d.maxGrade, recruiting: d.recruiting,
    })).catch(e => setError(e.message));
  }, [teamId]);

  const set = <K extends keyof TeamUpsert>(k: K, v: TeamUpsert[K]) => setT(prev => ({ ...prev, [k]: v }));
  const toggleProgram = (p: string) =>
    set('programs', t.programs.includes(p) ? t.programs.filter(x => x !== p) : [...t.programs, p]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { ...t, contactEmail: t.contactEmail || null };
    try {
      const saved = teamId
        ? await api<TeamDetail>(`/api/my/teams/${teamId}`, { method: 'PUT', body })
        : await api<TeamDetail>('/api/my/teams', { method: 'POST', body });
      nav(teamId ? '/manage' : `/manage/${saved.id}/form`);
    } catch (err) {
      setError((err as Error).message);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally { setBusy(false); }
  }

  return (
    <div className="container narrow">
      <form className="card" onSubmit={submit}>
        <h1>{teamId ? 'Edit team profile' : 'Add your team'}</h1>
        <ErrorBox error={error} />
        <div className="two-col">
          <label className="field"><span>Team name *</span><input required value={t.name} onChange={e => set('name', e.target.value)} /></label>
          <label className="field"><span>Team number</span><input value={t.teamNumber} onChange={e => set('teamNumber', e.target.value)} placeholder="e.g. 11115" /></label>
        </div>
        <div className="field">
          <span>Team type *</span>
          <div className="segmented">
            <button type="button" className={t.type === 'COMMUNITY' ? 'on' : ''} onClick={() => set('type', 'COMMUNITY')}>Community team</button>
            <button type="button" className={t.type === 'SCHOOL' ? 'on' : ''} onClick={() => set('type', 'SCHOOL')}>School team</button>
          </div>
        </div>
        {t.type === 'SCHOOL' && (
          <label className="field"><span>School</span><input value={t.schoolName} onChange={e => set('schoolName', e.target.value)} /></label>
        )}
        <div className="field">
          <span>Programs</span>
          <div className="chips">
            {programs.map(p => (
              <button type="button" key={p} className={`chip chip-toggle ${t.programs.includes(p) ? 'on' : ''}`} onClick={() => toggleProgram(p)}>{p}</button>
            ))}
          </div>
        </div>
        <label className="field"><span>About the team</span>
          <textarea rows={5} maxLength={4000} value={t.description} onChange={e => set('description', e.target.value)}
            placeholder="What you build, what students learn, what makes your team special…" /></label>
        <div className="three-col">
          <label className="field"><span>ZIP *</span><input required inputMode="numeric" maxLength={5} value={t.zip} onChange={e => set('zip', e.target.value.replace(/\D/g, ''))} /></label>
          <label className="field"><span>City</span><input value={t.city} onChange={e => set('city', e.target.value)} placeholder="auto from ZIP" /></label>
          <label className="field"><span>State</span><input maxLength={2} value={t.state} onChange={e => set('state', e.target.value.toUpperCase())} placeholder="auto" /></label>
        </div>
        <div className="two-col">
          <label className="field"><span>Youngest grade</span><GradeSelect value={t.minGrade} onChange={g => set('minGrade', g)} placeholder="Not specified" /></label>
          <label className="field"><span>Oldest grade</span><GradeSelect value={t.maxGrade} onChange={g => set('maxGrade', g)} placeholder="Not specified" /></label>
        </div>
        <div className="two-col">
          <label className="field"><span>Website</span><input value={t.websiteUrl} onChange={e => set('websiteUrl', e.target.value)} placeholder="yourteam.org" />
            <span className="small muted">We check that this opens when you save.</span></label>
          <label className="field"><span>Public contact email</span><input type="email" value={t.contactEmail} onChange={e => set('contactEmail', e.target.value)} /></label>
        </div>
        <label className="field"><span>When &amp; where you meet</span><input value={t.meetingInfo} onChange={e => set('meetingInfo', e.target.value)} /></label>
        <label className="field"><span>Cost</span><input value={t.costInfo} onChange={e => set('costInfo', e.target.value)} placeholder="e.g. Free / $150 per season, scholarships available" /></label>
        <label className="check"><input type="checkbox" checked={t.recruiting} onChange={e => set('recruiting', e.target.checked)} /> We're looking for new members</label>
        <div className="card-actions">
          <button type="button" className="btn btn-ghost" onClick={() => nav(-1)}>Cancel</button>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : teamId ? 'Save' : 'Create team & build application →'}</button>
        </div>
      </form>
    </div>
  );
}
