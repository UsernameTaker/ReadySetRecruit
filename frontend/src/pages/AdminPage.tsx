import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Claim, JobStatus } from '../types';
import { ErrorBox } from '../components/Common';

interface Stats { teams: number; claimedTeams: number; users: number; applications: number; job: JobStatus; }

export default function AdminPage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [claims, setClaims] = useState<Claim[]>([]);
  const [csv, setCsv] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = () => {
    api<Stats>('/api/admin/stats').then(setStats).catch(e => setError(e.message));
    api<Claim[]>('/api/admin/claims').then(setClaims).catch(e => setError(e.message));
  };
  useEffect(load, []);

  async function run(label: string, fn: () => Promise<unknown>) {
    setError(null);
    setNotice(null);
    try { await fn(); setNotice(`${label} started. Refresh in a bit to see how it went`); load(); }
    catch (e) { setError((e as Error).message); }
  }

  async function decide(id: number, approve: boolean) {
    try { await api(`/api/admin/claims/${id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' }); load(); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="container narrow">
      <h1>Admin</h1>
      <ErrorBox error={error} />
      {notice && <div className="alert alert-ok">{notice}</div>}
      {stats && (
        <div className="stats">
          <div className="card"><strong>{stats.teams}</strong><span>teams</span></div>
          <div className="card"><strong>{stats.claimedTeams}</strong><span>claimed</span></div>
          <div className="card"><strong>{stats.users}</strong><span>users</span></div>
          <div className="card"><strong>{stats.applications}</strong><span>applications</span></div>
        </div>
      )}

      <div className="card">
        <h2>Claim requests</h2>
        {claims.length === 0 && <p className="muted">Nothing to review.</p>}
        {claims.map(c => (
          <div key={c.id} className="row-card">
            <div>
              <strong><Link to={`/teams/${c.teamId}`}>{c.teamName}</Link></strong>
              <p className="small">{c.userName} · <a href={`mailto:${c.userEmail}`}>{c.userEmail}</a></p>
              {c.message && <p className="small muted pre">{c.message}</p>}
            </div>
            <div className="row-actions">
              <button className="btn btn-ghost danger" onClick={() => decide(c.id, false)}>Reject</button>
              <button className="btn btn-primary" onClick={() => decide(c.id, true)}>Approve</button>
            </div>
          </div>
        ))}
      </div>

      <div className="card">
        <h2>Background jobs</h2>
        {stats && <p className="small muted">Last job: {stats.job.name ?? '—'} · {stats.job.running ? 'running…' : stats.job.summary}</p>}
        <div className="card-actions">
          <button className="btn btn-secondary" onClick={() => run('Website check', () => api('/api/admin/jobs/scrape', { method: 'POST' }))}>Run website scraper</button>
          <button className="btn btn-secondary" onClick={() => run('FRC import', () => api('/api/admin/jobs/import-frc', { method: 'POST' }))}>Import FRC teams (TBA)</button>
          <button className="btn btn-secondary" onClick={() => run('FTC import', () => api('/api/admin/jobs/import-ftc', { method: 'POST' }))}>Import FTC teams (FTCScout)</button>
          <button className="btn btn-secondary" onClick={() => run('VEX import', () => api('/api/admin/jobs/import-vex', { method: 'POST' }))}>Import VEX teams (RobotEvents)</button>
          <button className="btn btn-ghost" onClick={load}>Refresh</button>
        </div>
        <h3>Import from CSV</h3>
        <p className="small muted">Columns: name, teamNumber, type (COMMUNITY/SCHOOL), programs (separated by ;), schoolName, city, state, zip, website, minGrade, maxGrade</p>
        <textarea rows={5} value={csv} onChange={e => setCsv(e.target.value)}
          placeholder={'name,type,programs,city,state,zip,website\nCircuit Kids,COMMUNITY,VEX IQ,Austin,TX,78701,circuitkids.org'} />
        <input type="file" accept=".csv,text/csv" onChange={async e => { const f = e.target.files?.[0]; if (f) setCsv(await f.text()); }} />
        <div className="card-actions">
          <button className="btn btn-primary" disabled={!csv.trim()} onClick={() => run('CSV import', () => api('/api/admin/jobs/import-csv', { method: 'POST', raw: csv }))}>Import CSV</button>
        </div>
      </div>
    </div>
  );
}
