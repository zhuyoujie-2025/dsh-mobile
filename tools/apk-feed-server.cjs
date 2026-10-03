// apk-feed-server.cjs — DSHMobile.apk 内网更新订阅源 (零依赖)
// 监听 0.0.0.0:3093, 只服务 GET /dshmobile/version.json 与 /dshmobile/DSHMobile.apk
// 数据目录 = 项目 dist/ (build.sh 每次构建会刷新 version.json)
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const PORT=Number(process.env.APK_FEED_PORT||3093);
const ROOT=process.env.APK_FEED_ROOT||path.join(__dirname,'..','dist');

http.createServer(function(req,res){
  if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405);res.end();return}
  var p=decodeURIComponent(String(req.url||'').split('?')[0]);
  if(!p.startsWith('/dshmobile/')){res.writeHead(404);res.end('not found');return}
  var name=p.slice('/dshmobile/'.length);
  if(!/^[A-Za-z0-9._-]+$/.test(name)||name.indexOf('..')>=0){res.writeHead(400);res.end();return}
  var f=path.join(ROOT,name);
  fs.stat(f,function(e,st){
    if(e||!st.isFile()){res.writeHead(404);res.end('not found');return}
    var ct=name.endsWith('.json')?'application/json; charset=utf-8'
        :name.endsWith('.apk')?'application/vnd.android.package-archive'
        :'application/octet-stream';
    res.writeHead(200,{'Content-Type':ct,'Content-Length':st.size,'Cache-Control':'no-store'});
    if(req.method==='HEAD'){res.end();return}
    fs.createReadStream(f).pipe(res);
  });
}).listen(PORT,'0.0.0.0',function(){console.log('[apk-feed] listening 0.0.0.0:'+PORT+' root='+ROOT)});
