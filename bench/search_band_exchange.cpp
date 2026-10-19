// Exact joint construction: exchange complete Test 15 vertical bands, then
// optimize Test 11's suffix order. No Test 15 rectangle crosses a band cut.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cmath>
#include <fstream>
#include <iostream>
#include <mutex>
#include <numeric>
#include <random>
#include <string>
#include <thread>
#include <vector>
using namespace std;
struct Pipe {int s,e,l,y;};
struct Test {vector<Pipe>p;vector<int>c;};
Test read(int k,const string&path){
 ifstream f("bench/tests/"+to_string(k)+".in"),g(path);double w;int n;f>>w>>n;
 Test t;t.c.resize(n);for(int s=0;s<n;s++){int c;f>>c;t.c[s]=c;if(c>0){int y;g>>y;t.p.push_back({s,n,c,y});}else t.p[-c-1].e=s;}return t;
}
struct Tree {
 static constexpr int N=16384;vector<int>mx,lazy;
 Tree():mx(2*N),lazy(2*N,-1){}
 void clear(){fill(mx.begin(),mx.end(),0);fill(lazy.begin(),lazy.end(),-1);}
 void push(int v){if(lazy[v]>=0){mx[v*2]=mx[v*2+1]=lazy[v];lazy[v*2]=lazy[v*2+1]=lazy[v];lazy[v]=-1;}}
 int query(int v,int l,int r,int a,int b){if(a<=l&&r<=b)return mx[v];push(v);int m=(l+r)/2,z=0;if(a<m)z=query(v*2,l,m,a,b);if(b>m)z=max(z,query(v*2+1,m,r,a,b));return z;}
 void set(int v,int l,int r,int a,int b,int z){if(a<=l&&r<=b){mx[v]=lazy[v]=z;return;}push(v);int m=(l+r)/2;if(a<m)set(v*2,l,m,a,b,z);if(b>m)set(v*2+1,m,r,a,b,z);mx[v]=max(mx[v*2],mx[v*2+1]);}
};
int main(int argc,char**argv){
 int seconds=argc>1?stoi(argv[1]):180,threads=argc>2?stoi(argv[2]):16;
 bool atomMode=argc>3&&string(argv[3])=="atoms";
 bool holesMode=argc>3&&string(argv[3])=="holes";
 bool focus=argc>4&&!atomMode&&!holesMode;int focusLo=focus?stoi(argv[3]):0,focusWidth=focus?stoi(argv[4]):0;
 Test a=read(11,"target/test11-freed4.txt"),b=read(15,"target/candidate-best15.txt");
 int na=a.p.size(),n=na-4006,H=418780;vector<int>diff(H+2);for(auto p:b.p)if(p.l>1){diff[p.y+1]++;diff[p.y+p.l]--;}
 vector<int>cuts;int live=0;for(int y=0;y<=H;y++){live+=diff[y];if(!live)cuts.push_back(y);}
 vector<pair<int,int>>bands;
 for(int lo:cuts){if(lo<3||lo>=10298)continue;for(int width:{7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,40,48,56,64}){
  if(!binary_search(cuts.begin(),cuts.end(),lo+width))continue;
  int prefixload=0;for(int i=0;i<4006;i++)if(a.p[i].y>=lo&&a.p[i].y<lo+width&&a.p[i].s<=9818&&9818<a.p[i].e)prefixload+=a.p[i].l;
  if(prefixload<=3)bands.emplace_back(lo,width);
 }}
 vector<pair<int,int>>atoms;vector<int>atomEnd;
 for(int k=0;k+1<int(cuts.size())&&cuts[k+1]<=20361;k++){
  int en=0;for(int i=0;i<4006;i++)if(cuts[k]<=a.p[i].y&&a.p[i].y<cuts[k+1]&&a.p[i].e>4999)en=max(en,a.p[i].e);
  atoms.emplace_back(cuts[k],cuts[k+1]);atomEnd.push_back(en);
 }
 if(focus){bands.clear();for(int t=0;t<threads;t++)bands.emplace_back(focusLo,focusWidth);}
 if(atomMode){bands.clear();for(int t=0;t<threads;t++)bands.emplace_back(0,0);}
 if(holesMode){bands.clear();for(int t=0;t<threads;t++)bands.emplace_back(0,0);}
 atomic<int>next(0),best(999999);atomic<bool>done(false);mutex lock;vector<int>globalOrder;
 auto start=chrono::steady_clock::now();
 cout<<"BANDS "<<bands.size()<<" threads "<<threads<<endl;
 auto worker=[&](int tid){
  mt19937 rng(87171+tid*12347);uniform_real_distribution<double>uni(0,1);Tree tree;
  vector<int>fixed(4006),initial(n),order,trial,pos(n),bestPos,localBestOrder;
  vector<vector<pair<int,int>>>domains(n);
  long long total=0;
  while(!done){
   int job=next++;if(job>=int(bands.size()))break;auto [lo,width]=bands[job];
   auto elapsed=[&](){return chrono::duration<double>(chrono::steady_clock::now()-start).count();};
   if(elapsed()>seconds)break;
   // Exchange [lo,lo+width) and [20348,20361), translating the middle.
   vector<int>mapping(20361);iota(mapping.begin(),mapping.end(),0);
   vector<pair<int,int>>removed;
   if(holesMode){
    int mask=1+tid%7;vector<pair<int,int>>options{{686,687},{1518,1524},{112,118}};
    for(int k=0;k<3;k++)if(mask&(1<<k))removed.push_back(options[k]);
    vector<int>perm;for(int z=0;z<20361;z++){bool gone=false;for(auto [x,y]:removed)if(x<=z&&z<y)gone=true;if(!gone)perm.push_back(z);}
    for(auto [x,y]:removed)for(int z=x;z<y;z++)perm.push_back(z);
    for(int z=0;z<20361;z++)mapping[perm[z]]=z;
   }else if(atomMode){
    vector<int>perm(atoms.size());iota(perm.begin(),perm.end(),0);
    stable_sort(perm.begin()+1,perm.end(),[&](int x,int y){
     if((atomEnd[x]==9999)!=(atomEnd[y]==9999))return atomEnd[x]==9999;
     if((atomEnd[x]>4999)!=(atomEnd[y]>4999))return atomEnd[x]>4999;
     if(atomEnd[x]==9999)return atoms[x].first<atoms[y].first;
     if(atomEnd[x]!=atomEnd[y])return tid%2?atomEnd[x]<atomEnd[y]:atomEnd[x]>atomEnd[y];
     return atoms[x].first<atoms[y].first;
    });
    int at=0;for(int k:perm){auto [x,y]=atoms[k];for(int z=x;z<y;z++)mapping[z]=at+z-x;at+=y-x;}
   }else for(int y=lo;y<20361;y++)mapping[y]=y<lo+width?20361-width+(y-lo):y<20348?y+13-width:lo+y-20348;
   auto mapped=[&](int y){return y<20361?mapping[y]:y;};
   vector<int>sorted(4006);iota(sorted.begin(),sorted.end(),0);for(int i=0;i<4006;i++)fixed[i]=mapped(a.p[i].y);
   sort(sorted.begin(),sorted.end(),[&](int i,int j){return fixed[i]<fixed[j];});
   for(int k=0;k<n;k++){
    int i=k+4006,cur=0;domains[k].clear();auto p=a.p[i];
    for(int j:sorted)if(max(p.s,a.p[j].s)<min(p.e,a.p[j].e)){
     int x=fixed[j],end=x+a.p[j].l;
     if(x-cur>=p.l)domains[k].emplace_back(cur,x-p.l);
     cur=max(cur,end);
    }
    domains[k].emplace_back(cur,1000000);initial[k]=k;
   }
   auto suffixRank=[&](int y){if(holesMode){int z=y;for(auto [x,end]:removed){if(y>=end)z-=end-x;else if(y>x)z-=y-x;}return z;}return mapped(y);};
   sort(initial.begin(),initial.end(),[&](int i,int j){int x=suffixRank(a.p[i+4006].y),y=suffixRank(a.p[j+4006].y);return x!=y?x<y:i<j;});
   auto evaluate=[&](const vector<int>&perm){
    tree.clear();int h=0;long long excess=0;
    for(int k:perm){auto p=a.p[k+4006];int lb=tree.query(1,0,Tree::N,p.s,p.e),z=1000000;
     for(auto [x,y]:domains[k])if(y>=lb){z=max(x,lb);break;}
     pos[k]=z;int top=z+p.l;tree.set(1,0,Tree::N,p.s,p.e,top);h=max(h,top);excess+=max(0,top-20760);
    }
    return pair<int,double>{h,h+excess*.0001};
   };
   order=initial;auto current=evaluate(order);int localBest=current.first;localBestOrder=order;
   auto save=[&](){
    if(current.first>=best.load())return;
    lock_guard<mutex>guard(lock);if(current.first>=best.load())return;best=current.first;
    cout<<"BEST "<<current.first<<" band "<<lo<<" "<<width<<" job "<<job<<" steps "<<total<<" seconds "<<elapsed()<<endl;
    ofstream f("target/band-exchange-11.tmp"),g("target/band-exchange-15.tmp");
    for(int i=0;i<na;i++)f<<(i<4006?fixed[i]:pos[i-4006])<<'\n';
    for(auto p:b.p)g<<mapped(p.y)<<'\n';
    f.close();g.close();
    // Publish only complete files. Readers should still replay the full pair
    // after the bounded search ends, because two files are not one transaction.
    remove("target/band-exchange-11.txt");rename("target/band-exchange-11.tmp","target/band-exchange-11.txt");
    remove("target/band-exchange-15.txt");rename("target/band-exchange-15.tmp","target/band-exchange-15.txt");
    if(current.first<=20760)done=true;
   };
   save();
   // Fast survey all transformations first. Promising bands receive a short
   // order search; later runs can focus on the best transformed plan.
   int steps=focus||atomMode||holesMode?1000000000:current.first<=20795?120:current.first<=20850?12:0;
   for(int it=0;it<steps&&!done;it++){
    if(elapsed()>seconds)break;
    trial=order;int x=rng()%n,z=rng()%n,mode=rng()%8;
    if(mode<5){vector<int>critical;for(int k=0;k<n;k++)if(pos[order[k]]+a.p[order[k]+4006].l>20760)critical.push_back(k);if(!critical.empty())x=critical[rng()%critical.size()];}
    if(mode<3){int span=mode==0?4:mode==1?30:150;z=max(0,min(n-1,x+int(rng()%(2*span+1))-span));}
    if((focus||atomMode||holesMode)&&mode==7){
     vector<int>high,peak;for(int k=0;k<n;k++){auto p=a.p[order[k]+4006];if(p.s<=9818&&9818<p.e){peak.push_back(k);if(pos[order[k]]>=20300)high.push_back(k);}}
     if(!high.empty())x=high[rng()%high.size()];if(!peak.empty())z=peak[rng()%peak.size()];
    }
    if(mode<6||mode==7)swap(trial[x],trial[z]);else{int id=trial[x];trial.erase(trial.begin()+x);trial.insert(trial.begin()+z,id);}
    auto cand=evaluate(trial);total++;double phase=(it%4000)/4000.;double temp=(tid%4==0?.05:tid%4==1?1.:tid%4==2?4.:12.)*(1-phase)+.001;
    if(cand.second<=current.second||uni(rng)<exp((current.second-cand.second)/temp)){order.swap(trial);current=cand;}else current=evaluate(order);
    if(current.first<localBest){localBest=current.first;localBestOrder=order;}save();
    if((focus||atomMode||holesMode)&&it%4000==3999){order=localBestOrder;current=evaluate(order);if(tid==0){lock_guard<mutex>guard(lock);cout<<"STEP "<<it+1<<" local "<<localBest<<" best "<<best<<" seconds "<<elapsed()<<endl;}}
   }
   if(job%50==0){lock_guard<mutex>guard(lock);cout<<"JOB "<<job<<" height "<<localBest<<" best "<<best<<" seconds "<<elapsed()<<endl;}
  }
 };
 vector<thread>pool;for(int t=0;t<threads;t++)pool.emplace_back(worker,t);for(auto&t:pool)t.join();cout<<"FINISHED "<<best<<" jobs "<<next<<endl;
}
