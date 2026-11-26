const LOGO='<svg viewBox="0 0 24 24"><path d="M12 2C12 2 5 10 5 15a7 7 0 0 0 14 0C19 10 12 2 12 2z" fill="#d62839"/><path d="M12 9v6M9 12h6" stroke="#fff" stroke-width="2" stroke-linecap="round"/></svg>';
async function api(path,data,method){
  const o={method:method||(data?'POST':'GET')};
  if(data)o.body=new URLSearchParams(data);
  const r=await fetch('/api/'+path,o);const j=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(j.error||'Something went wrong');
  return j;
}
function esc(s){return String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function toast(msg,ok=true){
  const t=document.createElement('div');t.className='toast'+(ok?'':' bad');t.textContent=msg;
  document.body.appendChild(t);setTimeout(()=>t.remove(),3500);
}
const USER_LINKS=[['dashboard.html','Dashboard'],['search.html','Find Donors'],['request.html','Request Blood'],['history.html','My Donations'],['profile.html','Profile']];
const ADMIN_LINKS=[['admin.html','Donors'],['hospitals.html','Hospitals'],['approvals.html','Approvals'],['stock.html','Stock & Reports'],['logs.html','Logs']];
function nav(u){
  const cur=location.pathname.split('/').pop()||'index.html';
  let h='<a class="logo" href="index.html">'+LOGO+'BDEN</a><a class="l" href="index.html">Home</a>';
  if(u)(u.role==='ADMIN'?ADMIN_LINKS:USER_LINKS).forEach(([f,t])=>h+=`<a class="l ${cur===f?'on':''}" href="${f}">${t}</a>`);
  h+=u?'<a class="btn sm" href="#" onclick="logout();return false">Logout</a>':'<a class="btn sm" href="auth.html">Login / Register</a>';
  document.getElementById('nav').innerHTML=h;
}
async function logout(){await api('logout',{});location.href='index.html'}
async function boot(need){
  const {user}=await api('me');
  if(need&&!user){location.href='auth.html';return null}
  if(need==='ADMIN'&&user.role!=='ADMIN'){location.href='dashboard.html';return null}
  if(need==='USER'&&user.role==='ADMIN'){location.href='admin.html';return null}
  nav(user);return user;
}
