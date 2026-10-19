// Exact disjunctive graph search. Rebuild Test 11's complete order cover after
// each permutation, keep Test 15's order cover, then compact their union.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <map>
#include <mutex>
#include <numeric>
#include <random>
#include <set>
#include <thread>
#include <vector>
using namespace std;
struct Pipe{int s,e,l,base;};
struct Test{vector<Pipe> p;vector<int> cmd,ids;};
vector<Pipe> nodes;vector<vector<int>> fixedGraph;Test a,b;
void load(Test &test,int number,string name){
 ifstream f("bench/tests/"+to_string(number)+".in"),g(name);double w;int n;f>>w>>n;test.cmd.resize(n);
 for(int t=0;t<n;t++){int &c=test.cmd[t];f>>c;if(c>0){int z;g>>z;int i=test.p.size();test.p.push_back({t,n,c,z});
  if(number==15&&i<4006){if(nodes[i].base!=z)throw 1;test.ids.push_back(i);}
  else{test.ids.push_back(nodes.size());nodes.push_back({t,n,c,z});}
 }else test.p[-c-1].e=t;}
}
vector<pair<int,int>> cover(const Test&t,const vector<int>&rank){
 set<pair<int,int>> live;vector<pair<int,int>> ed;int k=0;
 for(int c:t.cmd){if(c>0){int id=t.ids[k++];auto it=live.emplace(rank[id],id).first;
   if(it!=live.begin())ed.emplace_back(prev(it)->second,id);
   if(next(it)!=live.end())ed.emplace_back(id,next(it)->second);
  }else{int id=t.ids[-c-1];auto it=live.find({rank[id],id});auto nx=next(it);
   if(it!=live.begin()&&nx!=live.end())ed.emplace_back(prev(it)->second,nx->second);live.erase(it);
  }}
 sort(ed.begin(),ed.end());ed.erase(unique(ed.begin(),ed.end()),ed.end());return ed;
}
int main(int argc,char**argv){
 int seconds=argc>1?stoi(argv[1]):300,workers=argc>2?stoi(argv[2]):16;
 bool only11=argc>5&&string(argv[5])=="only11";
 load(a,11,argc>3?argv[3]:"target/test11-freed4.txt");if(!only11)load(b,15,argc>4?argv[4]:"target/candidate-best15.txt");
 int n=nodes.size(),na=a.p.size();vector<int> baseRank(n),full(n);iota(full.begin(),full.end(),0);
 sort(full.begin(),full.end(),[](int i,int j){return nodes[i].base!=nodes[j].base?nodes[i].base<nodes[j].base:i<j;});
 for(int i=0;i<n;i++)baseRank[full[i]]=i;
 fixedGraph.resize(n);vector<int>fixedIn(n);for(auto [u,v]:cover(b,baseRank)){fixedGraph[u].push_back(v);fixedIn[v]++;}
 vector<int> initial(na);iota(initial.begin(),initial.end(),0);sort(initial.begin(),initial.end(),[&](int i,int j){return baseRank[i]<baseRank[j];});
 mutex guard;atomic<bool>done(false);double bestScore=1e100;vector<int>bestOrder=initial;int best11=999999,best15=999999;
 auto started=chrono::steady_clock::now();
 auto worker=[&](int tid){
  mt19937 rng(17171+tid*9337);uniform_real_distribution<double>uni(0,1);
  vector<int>order=initial,trial,rank=baseRank,y(n),parent(n),indegree(n),queue(n),at(na),critical;
  vector<vector<int>>graph(n);long long iterations=0,accepted=0;
  auto evaluate=[&](const vector<int>&perm){
   for(int i=0;i<na;i++)rank[perm[i]]=i;
   auto edges=cover(a,rank);
   for(int i=0;i<n;i++)graph[i].clear();
   indegree=fixedIn;for(auto [u,v]:edges){graph[u].push_back(v);indegree[v]++;}
   fill(y.begin(),y.end(),0);fill(parent.begin(),parent.end(),-1);int head=0,tail=0;
   for(int i=0;i<n;i++)if(!indegree[i])queue[tail++]=i;
   while(head<tail){int u=queue[head++],end=y[u]+nodes[u].l;
    for(auto *arcs:{&fixedGraph[u],&graph[u]})for(int v:*arcs){if(y[v]<end){y[v]=end;parent[v]=u;}if(!--indegree[v])queue[tail++]=v;}}
   if(tail!=n)return pair<int,int>{999999,999999};
   int h11=0,h15=0;for(int i:a.ids)h11=max(h11,y[i]+nodes[i].l);for(int i:b.ids)h15=max(h15,y[i]+nodes[i].l);
   return pair<int,int>{h11,h15};
  };
  auto value=[&](pair<int,int>h){return 2.0*max(0,h.first-20760)+max(0,h.second-418780);};
  auto current=evaluate(order);double score=value(current);
  while(!done){
   double elapsed=chrono::duration<double>(chrono::steady_clock::now()-started).count();if(elapsed>seconds)break;
   {lock_guard<mutex> lock(guard);if(score<bestScore){bestScore=score;bestOrder=order;best11=current.first;best15=current.second;
     cout<<"BEST "<<current.first<<" "<<current.second<<" excess "<<score<<" thread "<<tid<<" time "<<elapsed<<" iterations "<<iterations<<endl;
     for(auto *test:{&a,&b}){if(test->ids.empty())continue;ofstream f(test==&a?(only11?"target/free-order-11.txt":"target/joint-order-11.txt"):"target/joint-order-15.txt");for(int i:test->ids)f<<y[i]<<'\n';}
     if(current.first<=20760&&current.second<=418780)done=true;
   }}
   for(int i=0;i<na;i++)at[order[i]]=i;
   critical.clear();int endpoint=-1;
   if(current.first<999999){for(int i:a.ids)if(endpoint<0||y[i]+nodes[i].l>y[endpoint]+nodes[endpoint].l)endpoint=i;
    for(int i=endpoint;i>=0;i=parent[i])if(i<na)critical.push_back(i);}
   trial=order;int x=rng()%na,z=rng()%na;int mode=rng()%10;
   vector<int>suffixCritical;for(int i:critical)if(i>=4006)suffixCritical.push_back(i);
   if(!critical.empty()&&mode<9){int i=(!suffixCritical.empty()&&rng()%20)?suffixCritical[rng()%suffixCritical.size()]:critical[rng()%critical.size()];x=at[i];
    if(mode<4&&parent[i]>=0&&parent[i]<na)z=at[parent[i]];
    else if(mode==4||mode==5||mode==8){
     vector<int>partners;for(int j=4006;j<na;j++)if(j!=i&&max(a.p[i].s,a.p[j].s)<min(a.p[i].e,a.p[j].e)
       &&abs(nodes[j].l-nodes[i].l)<=(mode==4?0:mode==5?3:7)
       &&(y[i]>=20300?y[j]<20300:y[j]>=20300))partners.push_back(j);
     if(!partners.empty())z=at[partners[rng()%partners.size()]];
    }else{int span=mode==6?5:mode==7?30:200;z=max(0,min(na-1,x+int(rng()%(2*span+1))-span));}}
   if(x==z)continue;
   if(mode<4&&rng()%3){int id=trial[x];trial.erase(trial.begin()+x);trial.insert(trial.begin()+z,id);}
   else swap(trial[x],trial[z]);
   // Compensating cross-corridor swaps permit a coordinated exchange instead
   // of requiring a single pipe to fit into a sub-8-meter hole.
   if(mode==8){
    vector<int>lo,hi;for(int j=4006;j<na;j++)if(a.p[j].s<=9818&&9818<a.p[j].e)(y[j]>=20300?hi:lo).push_back(j);
    if(!lo.empty()&&!hi.empty()){int i=hi[rng()%hi.size()],j=lo[rng()%lo.size()];swap(trial[at[i]],trial[at[j]]);}
   }
   auto candidate=evaluate(trial);double nextScore=value(candidate);iterations++;
   double phase=(iterations%3000)/3000.;double temp=(tid%4==0?0.05:tid%4==1?1.:tid%4==2?4.:12.)*(1-phase)+.001;
   if(nextScore<=score||uni(rng)<exp((score-nextScore)/temp)){order.swap(trial);current=candidate;score=nextScore;accepted++;}
   else current=evaluate(order);
   if(iterations%1000==0){lock_guard<mutex> lock(guard);
    if(tid==0)cout<<"STEP "<<iterations<<" local "<<current.first<<" "<<current.second<<" accepted "<<accepted<<" best "<<best11<<" "<<best15<<" time "<<elapsed<<endl;
    if(score>bestScore+10||uni(rng)<.3){order=bestOrder;current=evaluate(order);score=value(current);}}
  }
 };
 vector<thread>pool;for(int i=0;i<workers;i++)pool.emplace_back(worker,i);for(auto &t:pool)t.join();
 cout<<"FINISHED "<<best11<<" "<<best15<<endl;
}
