import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import type { TeamSummary } from '../types';
import TeamCard from '../components/TeamCard';
import { ErrorBox, GradeSelect } from '../components/Common';

export default function Home() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const [programs, setPrograms] = useState<string[]>([]);
  const [zip, setZip] = useState(params.get('zip') ?? '');
  const [radius, setRadius] = useState(params.get('radius') ?? '25');
  const [grade, setGrade] = useState<number | null>(params.get('grade') ? Number(params.get('grade')) : null);
  const [program, setProgram] = useState(params.get('program') ?? '');
  const [type, setType] = useState(params.get('type') ?? '');
  const [openOnly, setOpenOnly] = useState(params.get('openApplications') === 'true');
  const [q, setQ] = useState(params.get('q') ?? '');
  const [results, setResults] = useState<TeamSummary[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { api<string[]>('/api/meta/programs').then(setPrograms).catch(() => {}); }, []);

  // Prefill from the student's profile the first time.
  useEffect(() => {
    if (user && !params.get('zip') && !zip && user.zip) setZip(user.zip);
    if (user && !params.get('grade') && grade == null && user.grade != null) setGrade(user.grade);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  // Run the search whenever the URL query changes (so results are shareable/bookmarkable).
  useEffect(() => {
    if (!params.get('zip') && !params.get('q')) { setResults(null); return; }
    setLoading(true);
    setError(null);
    api<TeamSummary[]>(`/api/teams/search?${params.toString()}`)
      .then(setResults)
      .catch(e => { setError(e.message); setResults(null); })
      .finally(() => setLoading(false));
  }, [params]);

  const zipOk = /^\d{5}$/.test(zip);

  function buildParams() {
    const p = new URLSearchParams();
    if (zipOk) { p.set('zip', zip); p.set('radius', radius); }
    if (grade != null) p.set('grade', String(grade));
    if (program) p.set('program', program);
    if (type) p.set('type', type);
    if (openOnly) p.set('openApplications', 'true');
    if (q.trim()) p.set('q', q.trim());
    return p;
  }

  // Search again whenever a filter changes (short pause so typing a ZIP or name doesn't fire on every key).
  useEffect(() => {
    if (!zipOk && !q.trim()) return;
    const timer = setTimeout(() => {
      const p = buildParams();
      if (p.toString() !== params.toString()) { setError(null); setParams(p, { replace: true }); }
    }, 400);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zip, radius, grade, program, type, openOnly, q]);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!zipOk && !q.trim()) { setError('Enter a 5-digit ZIP code (or search by name).'); return; }
    setParams(buildParams());
  }

  return (
    <>
      <section className="hero">
        <div className="container">
          <h1>Your next team starts here!</h1>
          <p className="lead">Discover FIRST and VEX robotics teams near you. Find your fit, connect with a team, and take the first step toward joining the robotics community.</p>
          <form className="search card" onSubmit={submit}>
            <div className="search-row">
              <label className="field grow">
                <span>ZIP code</span>
                <input inputMode="numeric" maxLength={5} placeholder="e.g. 94040" value={zip}
                  onChange={e => setZip(e.target.value.replace(/\D/g, ''))} />
              </label>
              <label className="field">
                <span>Distance</span>
                <select value={radius} onChange={e => setRadius(e.target.value)}>
                  {[5, 10, 25, 50, 100].map(r => <option key={r} value={r}>{r} miles</option>)}
                </select>
              </label>
              <label className="field">
                <span>Grade <em className="muted">(optional)</em></span>
                <GradeSelect value={grade} onChange={setGrade} />
              </label>
              <button className="btn btn-primary" type="submit">Search</button>
            </div>
            <details className="more-filters" open={!!(program || type || openOnly || q)}>
              <summary>More filters</summary>
              <div className="search-row">
                <label className="field">
                  <span>Program</span>
                  <select value={program} onChange={e => setProgram(e.target.value)}>
                    <option value="">Any program</option>
                    {programs.map(p => <option key={p}>{p}</option>)}
                  </select>
                </label>
                <label className="field">
                  <span>Team type</span>
                  <select value={type} onChange={e => setType(e.target.value)}>
                    <option value="">Community &amp; school</option>
                    <option value="COMMUNITY">Community teams</option>
                    <option value="SCHOOL">School teams</option>
                  </select>
                </label>
                <label className="field grow">
                  <span>Name / number / school</span>
                  <input value={q} onChange={e => setQ(e.target.value)} placeholder="optional" />
                </label>
                <label className="check">
                  <input type="checkbox" checked={openOnly} onChange={e => setOpenOnly(e.target.checked)} />
                  Only teams taking applications
                </label>
              </div>
            </details>
          </form>
        </div>
      </section>

      <section className="container results">
        <ErrorBox error={error} />
        {loading && <p className="muted">Searching…</p>}
        {!loading && results && (
          <>
            <p className="muted">{results.length === 0 ? 'No teams found. Try a bigger distance or fewer filters.'
              : `${results.length} team${results.length === 1 ? '' : 's'} found`}</p>
            <div className="grid">
              {results.map(t => <TeamCard key={t.id} team={t} />)}
            </div>
          </>
        )}
        {!results && !loading && (
          <div className="how">
            <div className="card"><h3>Students</h3><p>Search by ZIP code (and grade if you want), compare teams, then apply here or visit the team's website.</p></div>
            <div className="card"><h3>Teams</h3><p>Create a free profile, build an application with your own questions and word limits, and review applicants.</p></div>
            <div className="card"><h3>Teams not on ReadySetRecruit yet</h3><p>They're still listed from public FIRST team lists, with a link to their website when they have one.</p></div>
          </div>
        )}
      </section>
    </>
  );
}
