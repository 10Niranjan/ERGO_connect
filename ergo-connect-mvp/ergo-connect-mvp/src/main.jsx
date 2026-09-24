import React, { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const USERS = [
  ['admin@ergo.local','ERGO Admin','ERGO','ERGO Asia'],
  ['management@ergo.local','ERGO Management','ERGO','ERGO Asia'],
  ['kam@ergo.local','ERGO Key Account Manager','ERGO','ERGO Asia'],
  ['rfq@ergo.local','ERGO RFQ','ERGO','ERGO Asia'],
  ['npd@ergo.local','ERGO NPD','ERGO','ERGO Asia'],
  ['ofp@ergo.local','ERGO OFP','ERGO','ERGO Asia'],
  ['quality@ergo.local','ERGO Quality','ERGO','ERGO Asia'],
  ['buyer@cresco.local','Customer RFQ','CRESCO','Cresco Europe'],
  ['customer.npd@cresco.local','Customer NPD','CRESCO','Cresco Europe'],
  ['customer.ofp@cresco.local','Customer OFP','CRESCO','Cresco Europe'],
  ['customer.quality@cresco.local','Customer Quality','CRESCO','Cresco Europe'],
  ['customer.manager@cresco.local','Customer Key Account Manager','CRESCO','Cresco Europe'],
  ['sales@metalform.local','Supplier RFQ','METALFORM','MetalForm India'],
  ['supplier.npd@metalform.local','Supplier NPD','METALFORM','MetalForm India'],
  ['supplier.ofp@metalform.local','Supplier OFP','METALFORM','MetalForm India'],
  ['supplier.quality@metalform.local','Supplier Quality','METALFORM','MetalForm India'],
  ['supplier.manager@metalform.local','Supplier Key Account Manager','METALFORM','MetalForm India']
].map(([loginId, role, companyId, companyName]) => ({ loginId, password: 'password123', role, companyId, companyName, name: role }));

const seedRFQs = [
  { id:'RFQ-2026-001', rfqNumber:'RFQ-2026-001', rfqDate:'2026-04-01', customerCompanyId:'CRESCO', customer:'Cresco Europe', partNumber:'HP-2209', description:'Hopper side plate', annualVolume:8000, sopDate:'2026-08-01', quoteRequiredDate:'2026-04-15', status:'Under Comparison', remarks:'Need close review of tooling assumptions.', drawings:['HP-2209_Drawing.pdf'], specs:['PowderCoatingSpec.pdf'], other:[] },
  { id:'RFQ-2026-002', rfqNumber:'RFQ-2026-002', rfqDate:'2026-04-03', customerCompanyId:'CRESCO', customer:'Cresco Europe', partNumber:'GBX-117', description:'Gearbox cover', annualVolume:5000, sopDate:'2026-09-01', quoteRequiredDate:'2026-04-18', status:'Customer Approved', remarks:'Approved by customer. Ready for manual NPD conversion.', drawings:['GBX-117_RevC.pdf'], specs:[], other:['Customer_Email.pdf'] }
];

const seedQuotes = [
  { id:'Q-001', rfqId:'RFQ-2026-001', supplierCompanyId:'METALFORM', supplier:'MetalForm India', status:'Selected for Customer', currency:'EUR', toolingCost:1150, piecePrice:4.9, ppapPrice:5.6, moq:500, leadTimeDays:28, submittedDate:'2026-04-08', remarks:'Best technical response. Supplier needs packaging confirmation.' },
  { id:'Q-002', rfqId:'RFQ-2026-002', supplierCompanyId:'METALFORM', supplier:'AccuCast', status:'Submitted', currency:'USD', toolingCost:2200, piecePrice:8.6, ppapPrice:9.2, moq:300, leadTimeDays:35, submittedDate:'2026-04-10', remarks:'Die casting lead time acceptable.' }
];

const seedNPD = [
  { id:'NPD-001', rfqId:'RFQ-2026-002', customer:'Cresco Europe', supplier:'AccuCast', partNumber:'GBX-117', stage:'Trial Preparation', targetSampleDate:'2026-05-30', progress:62, status:'Active' }
];
const seedOrders = [{ id:'ORD-001', customer:'Cresco Europe', supplier:'MetalForm India', partNumber:'HP-2209', qty:1200, dueDate:'2026-05-15', status:'In Inspection', shipment:'Not dispatched' }];
const seedQuality = [{ id:'NCR-001', customer:'Cresco Europe', supplier:'MetalForm India', partNumber:'HP-2209', issue:'Scratch marks on coated surface', status:'CAPA in Progress' }];

const pages = [
  ['dashboard','Dashboard','📊'], ['rfq','RFQ','📄'], ['npd','NPD','🗂️'], ['ofp','OFP / Orders','📦'], ['quality','Quality','📋'], ['documents','Documents','📁'], ['communication','Communication','💬'], ['admin','Admin','⚙️']
];
function canAccess(page, role){
  if(['ERGO Admin','ERGO Management'].includes(role)) return true;
  if(page==='dashboard'||page==='documents'||page==='communication') return true;
  if(page==='rfq') return ['ERGO RFQ','ERGO Key Account Manager','Customer RFQ','Customer Key Account Manager','Supplier RFQ','Supplier Key Account Manager'].includes(role);
  if(page==='npd') return ['ERGO NPD','ERGO Key Account Manager','Customer NPD','Customer Key Account Manager','Supplier NPD','Supplier Key Account Manager'].includes(role);
  if(page==='ofp') return ['ERGO OFP','ERGO Key Account Manager','Customer OFP','Customer Key Account Manager','Supplier OFP','Supplier Key Account Manager'].includes(role);
  if(page==='quality') return ['ERGO Quality','ERGO Key Account Manager','Customer Quality','Customer Key Account Manager','Supplier Quality','Supplier Key Account Manager'].includes(role);
  if(page==='admin') return role==='ERGO Admin';
  return false;
}
function fmt(v){ return v || '-'; }
function money(c,v){ return v===undefined || v===null ? '-' : `${c} ${Number(v).toLocaleString()}`; }
function badge(status){
  const cls = ['Customer Approved','Selected for Customer','Closed','Delivered','Active'].includes(status) ? 'badge green' : ['Rejected','Blocked','Overdue'].includes(status) ? 'badge red' : 'badge';
  return <span className={cls}>{status}</span>;
}

function App(){
  const [user,setUser]=useState(null);
  const [loginId,setLoginId]=useState('rfq@ergo.local');
  const [password,setPassword]=useState('password123');
  const [loginError,setLoginError]=useState('');
  const [page,setPage]=useState('dashboard');
  const [rfqs,setRfqs]=useState(seedRFQs);
  const [quotes,setQuotes]=useState(seedQuotes);
  const [npd,setNpd]=useState(seedNPD);
  const [orders,setOrders]=useState(seedOrders);
  const [quality,setQuality]=useState(seedQuality);
  const [search,setSearch]=useState('');
  const [compact,setCompact]=useState('dense');
  const [showCreate,setShowCreate]=useState(false);
  const [expanded,setExpanded]=useState('');
  const [form,setForm]=useState({partNumber:'',description:'',annualVolume:'',sopDate:'',quoteRequiredDate:'',drawings:'',specs:'',other:''});

  const visiblePages = user ? pages.filter(p=>canAccess(p[0], user.role)) : [];
  const visibleRfqs = useMemo(()=>{
    let list = [...rfqs];
    if(user?.companyId && user.companyId!=='ERGO' && user.role.startsWith('Customer')) list = list.filter(r=>r.customerCompanyId===user.companyId);
    if(user?.companyId && user.companyId!=='ERGO' && user.role.startsWith('Supplier')) list = list.filter(r=>quotes.some(q=>q.rfqId===r.id && q.supplierCompanyId===user.companyId));
    const q = search.trim().toLowerCase();
    if(q) list = list.filter(r=>[r.rfqNumber,r.customer,r.partNumber,r.description,r.status].join(' ').toLowerCase().includes(q));
    return list;
  },[rfqs,quotes,user,search]);

  const summary = useMemo(()=>{
    const qty = visibleRfqs.reduce((s,r)=>s+(r.annualVolume||0),0);
    return { rfq: visibleRfqs.length, qty, usd: qty*5, inr: qty*400, ready: visibleRfqs.filter(r=>r.status==='Customer Approved').length };
  },[visibleRfqs]);

  function login(){
    const u = USERS.find(x=>x.loginId.toLowerCase()===loginId.trim().toLowerCase() && x.password===password);
    if(!u){setLoginError('Invalid Login ID or Password'); return;}
    setUser(u); setPage('dashboard'); setLoginError('');
  }
  function createRFQ(){
    const no = `RFQ-2026-${String(rfqs.length+1).padStart(3,'0')}`;
    const next = {
      id:no, rfqNumber:no, rfqDate:new Date().toISOString().slice(0,10), customerCompanyId:user.companyId==='ERGO'?'CRESCO':user.companyId, customer:user.companyId==='ERGO'?'Cresco Europe':user.companyName,
      partNumber:form.partNumber||'-', description:form.description||'-', annualVolume:Number(form.annualVolume||0), sopDate:form.sopDate, quoteRequiredDate:form.quoteRequiredDate,
      status:'Draft', remarks:'', drawings:form.drawings.split('\n').map(x=>x.trim()).filter(Boolean), specs:form.specs.split('\n').map(x=>x.trim()).filter(Boolean), other:form.other.split('\n').map(x=>x.trim()).filter(Boolean)
    };
    setRfqs([next,...rfqs]);
    setForm({partNumber:'',description:'',annualVolume:'',sopDate:'',quoteRequiredDate:'',drawings:'',specs:'',other:''});
    setShowCreate(false); setPage('rfq');
  }
  function convertToNPD(rfq){
    if(npd.some(n=>n.rfqId===rfq.id)) return;
    setNpd([{id:`NPD-${String(npd.length+1).padStart(3,'0')}`,rfqId:rfq.id,customer:rfq.customer,supplier:'TBD',partNumber:rfq.partNumber,stage:'Planning',targetSampleDate:'',progress:5,status:'Active'},...npd]);
  }

  if(!user) return <Login loginId={loginId} setLoginId={setLoginId} password={password} setPassword={setPassword} login={login} error={loginError}/>;

  return <div className="app">
    <aside>
      <div className="brand"><div className="logo">EC</div><div><b>ERGO Connect</b><small>MVP Starter</small></div></div>
      <div className="userbox"><b>{user.name}</b><span>{user.loginId}</span><span>{user.role}</span><button onClick={()=>setUser(null)}>Logout</button></div>
      <nav>{visiblePages.map(([key,label,icon])=><button key={key} onClick={()=>setPage(key)} className={page===key?'active':''}><span>{icon}</span>{label}</button>)}</nav>
    </aside>
    <main>
      <header><div><h1>{visiblePages.find(p=>p[0]===page)?.[1]||page}</h1><p>Role-based MVP built from ERGO Connect requirements.</p></div><div className="headActions"><input placeholder="Search" value={search} onChange={e=>setSearch(e.target.value)}/><select value={compact} onChange={e=>setCompact(e.target.value)}><option>normal</option><option>dense</option><option>ultra_dense</option></select>{['ERGO Admin','ERGO RFQ','Customer RFQ'].includes(user.role)&&<button className="primary" onClick={()=>setShowCreate(true)}>＋ New RFQ</button>}</div></header>
      <section>
        {page==='dashboard'&&<Dashboard summary={summary} isErgo={user.companyId==='ERGO'} npd={npd} orders={orders} quality={quality}/>} 
        {page==='rfq'&&<RFQList rfqs={visibleRfqs} quotes={quotes} compact={compact} expanded={expanded} setExpanded={setExpanded}/>} 
        {page==='npd'&&<NPD npd={npd} ready={visibleRfqs.filter(r=>r.status==='Customer Approved')} convert={convertToNPD}/>} 
        {page==='ofp'&&<SimpleTable title="OFP / Order Tracking" rows={orders}/>} 
        {page==='quality'&&<SimpleTable title="Quality Management" rows={quality}/>} 
        {page==='documents'&&<Documents rfqs={visibleRfqs}/>} 
        {page==='communication'&&<Communication/>} 
        {page==='admin'&&<Admin/>}
      </section>
    </main>
    {showCreate&&<CreateModal form={form} setForm={setForm} count={rfqs.length} close={()=>setShowCreate(false)} create={createRFQ}/>} 
  </div>;
}

function Login({loginId,setLoginId,password,setPassword,login,error}){return <div className="login"><div className="loginCard"><div className="logo big">EC</div><h1>ERGO Connect</h1><p>Login to continue</p><label>Login ID</label><input value={loginId} onChange={e=>setLoginId(e.target.value)}/><label>Password</label><input type="password" value={password} onChange={e=>setPassword(e.target.value)}/>{error&&<div className="err">{error}</div>}<button className="primary full" onClick={login}>Login</button><div className="demo"><b>Demo users, password: password123</b>{USERS.map(u=><div key={u.loginId}><b>{u.role}:</b> {u.loginId}</div>)}</div></div></div>}
function Dashboard({summary,isErgo,npd,orders,quality}){return <div className="cards"><Card title="RFQ Summary" icon="📄" count={summary.rfq} qty={summary.qty} value={isErgo?`₹ ${summary.inr.toLocaleString()}`:`$ ${summary.usd.toLocaleString()}`}/><Card title="NPD Summary" icon="🗂️" count={npd.length} qty={summary.qty} value={isErgo?`₹ ${summary.inr.toLocaleString()}`:`$ ${summary.usd.toLocaleString()}`}/><Card title="Quality Summary" icon="📋" count={quality.length} qty={1200} value={isErgo?'₹ 480,000':'$ 6,000'}/><Card title="OFP Summary" icon="📦" count={orders.length} qty={orders.reduce((s,o)=>s+o.qty,0)} value={isErgo?'₹ 480,000':'$ 6,000'}/></div>}
function Card({title,icon,count,qty,value}){return <div className="card"><div className="cardHead"><b>{title}</b><span>{icon}</span></div><div className="metric"><span>Count</span><b>{count}</b></div><div className="metric"><span>Qty | Value</span><b>{Number(qty||0).toLocaleString()} | {value}</b></div></div>}
function RFQList({rfqs,quotes,compact,expanded,setExpanded}){const pad=compact==='normal'?'normalRow':compact==='dense'?'denseRow':'ultraRow'; return <div className="panel"><h2>RFQ List</h2><p>All quote details appear in one row. Only remarks expand below the row.</p><div className="tableWrap"><table><thead><tr>{['RFQ #','RFQ Date','Customer','Part #','Description','Quote Status','Supplier','Currency','Tooling','Piece Price','MOQ','Lead Time','Quote Required Date','Quote Submitted Date','Remarks'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{rfqs.map(r=>{const q=quotes.find(x=>x.rfqId===r.id&&x.status==='Selected for Customer')||quotes.find(x=>x.rfqId===r.id); const open=expanded===r.id; return <React.Fragment key={r.id}><tr><td className={pad}><b>{r.rfqNumber}</b></td><td className={pad}>{fmt(r.rfqDate)}</td><td className={pad}>{r.customer}</td><td className={pad}>{r.partNumber}</td><td className={pad}>{r.description}</td><td className={pad}>{badge(q?.status||r.status)}</td><td className={pad}>{q?.supplier||'-'}</td><td className={pad}>{q?.currency||'-'}</td><td className={pad}>{money(q?.currency,q?.toolingCost)}</td><td className={pad}>{money(q?.currency,q?.piecePrice)}</td><td className={pad}>{q?.moq??'-'}</td><td className={pad}>{q?`${q.leadTimeDays} d`:'-'}</td><td className={pad}>{fmt(r.quoteRequiredDate)}</td><td className={pad}>{fmt(q?.submittedDate)}</td><td className={pad}><button onClick={()=>setExpanded(open?'':r.id)}>{open?'Hide':'Show'}</button></td></tr>{open&&<tr><td colSpan="15" className="remarks"><div><b>RFQ Remarks / Notes</b><p>{r.remarks||'No RFQ remarks available.'}</p></div><div><b>Quote Remarks / Notes</b><p>{q?.remarks||'No quote remarks available.'}</p></div></td></tr>}</React.Fragment>})}</tbody></table></div></div>}
function CreateModal({form,setForm,count,close,create}){return <div className="modal"><div className="modalCard"><div className="modalHead"><h2>Create RFQ</h2><button onClick={close}>✕</button></div><div className="grid"><Field label="RFQ #" value={`RFQ-2026-${String(count+1).padStart(3,'0')}`} readOnly/><Field label="RFQ Date" value={new Date().toISOString().slice(0,10)} readOnly/><Field label="Part #" value={form.partNumber} onChange={v=>setForm({...form,partNumber:v})}/><Field label="Annual Volume" type="number" value={form.annualVolume} onChange={v=>setForm({...form,annualVolume:v})}/><Field label="Part Description" textarea value={form.description} onChange={v=>setForm({...form,description:v})}/><Field label="SOP Date" type="date" value={form.sopDate} onChange={v=>setForm({...form,sopDate:v})}/><Field label="Quote Required Date" type="date" value={form.quoteRequiredDate} onChange={v=>setForm({...form,quoteRequiredDate:v})}/></div><div className="attach"><Field label="Attachments - Drawings" textarea value={form.drawings} onChange={v=>setForm({...form,drawings:v})} placeholder="One filename per line"/><Field label="Attachments - Specifications" textarea value={form.specs} onChange={v=>setForm({...form,specs:v})} placeholder="One filename per line"/><Field label="Attachments - Other" textarea value={form.other} onChange={v=>setForm({...form,other:v})} placeholder="One filename per line"/></div><div className="actions"><button onClick={close}>Cancel</button><button className="primary" onClick={create}>Create RFQ</button></div></div></div>}
function Field({label,value,onChange,type='text',readOnly,textarea,placeholder}){return <label className={textarea?'wide':''}><span>{label}</span>{textarea?<textarea value={value} readOnly={readOnly} placeholder={placeholder} onChange={e=>onChange?.(e.target.value)}/>:<input type={type} value={value} readOnly={readOnly} placeholder={placeholder} onChange={e=>onChange?.(e.target.value)}/>}</label>}
function NPD({npd,ready,convert}){return <div className="panel"><h2>NPD Management</h2><h3>Approved Projects Ready for NPD Conversion</h3>{ready.map(r=><div className="listItem" key={r.id}><span>{r.rfqNumber} • {r.partNumber}</span><button onClick={()=>convert(r)}>Convert to NPD</button></div>)}<h3>Active NPD Projects</h3>{npd.map(n=><div className="listItem" key={n.id}><span>{n.id} • {n.partNumber} • {n.stage} • {n.progress}%</span>{badge(n.status)}</div>)}</div>}
function SimpleTable({title,rows}){return <div className="panel"><h2>{title}</h2><div className="tableWrap"><table><thead><tr>{Object.keys(rows[0]||{}).map(k=><th key={k}>{k}</th>)}</tr></thead><tbody>{rows.map((r,i)=><tr key={i}>{Object.values(r).map((v,j)=><td key={j}>{String(v)}</td>)}</tr>)}</tbody></table></div></div>}
function Documents({rfqs}){return <div className="panel"><h2>Document Library</h2>{rfqs.map(r=><div className="listItem" key={r.id}><span>{r.rfqNumber}</span><span>Drawings: {r.drawings.join(', ')||'-'} | Specs: {r.specs.join(', ')||'-'} | Other: {r.other.join(', ')||'-'}</span></div>)}</div>}
function Communication(){return <div className="panel"><h2>Communication Center</h2><textarea placeholder="Add comment / note"/><button className="primary">Post Message</button></div>}
function Admin(){return <div className="panel"><h2>Admin Controls</h2><p>User setup, permission mapping, master data, audit logs and workflow settings will be added in production build.</p></div>}

createRoot(document.getElementById('root')).render(<App />);
