#include <algorithm>
#include <fstream>
#include <iostream>
#include <map>
#include <numeric>
#include <string>
#include <vector>
using namespace std;
struct Pipe {int len,y; vector<int> after;};
vector<Pipe> nodes;
void load(int test, string plan, vector<int>&ids){
 ifstream f("bench/tests/"+to_string(test)+".in"), a(plan);
 double weight;int n;f>>weight>>n;
 map<int,int> live;
 auto edge=[&](int u,int v){nodes[u].after.push_back(v);};
 for(int t=0;t<n;t++){
  int c;f>>c;
  if(c>0){int y;a>>y;int k=ids.size(),id;
   if(test==15&&k<4006){id=k;if(nodes[id].y!=y||nodes[id].len!=c)throw 1;}
   else{id=nodes.size();nodes.push_back({c,y,{}});}
   ids.push_back(id);auto it=live.emplace(y,id).first;
   if(it!=live.begin())edge(prev(it)->second,id);
   if(next(it)!=live.end())edge(id,next(it)->second);
  }else{int id=ids[-c-1];auto it=live.find(nodes[id].y);if(it==live.end()||it->second!=id)throw 2;
   auto nx=next(it);if(it!=live.begin()&&nx!=live.end())edge(prev(it)->second,nx->second);
   live.erase(it);
  }
 }
}
int main(int argc,char**argv){
 string a=argc>1?argv[1]:"target/test11-freed4.txt",b=argc>2?argv[2]:"target/candidate-best15.txt";
 vector<int>aa,bb;load(11,a,aa);if(argc<4||string(argv[3])!="only11")load(15,b,bb);
 vector<int>order(nodes.size()),y(nodes.size());iota(order.begin(),order.end(),0);
 sort(order.begin(),order.end(),[](int i,int j){return nodes[i].y<nodes[j].y;});
 long long edges=0;
 for(int i:order){auto &v=nodes[i].after;sort(v.begin(),v.end());v.erase(unique(v.begin(),v.end()),v.end());edges+=v.size();
  for(int j:v){if(nodes[j].y<nodes[i].y+nodes[i].len)throw 3;y[j]=max(y[j],y[i]+nodes[i].len);}}
 cout<<"nodes "<<nodes.size()<<" edges "<<edges<<endl;
 for(auto data:vector<pair<int,vector<int>*>>{{11,&aa},{15,&bb}}){int height=0,changes=0;ofstream f("target/compact-"+to_string(data.first)+".txt");
  for(int i:*data.second){f<<y[i]<<'\n';height=max(height,y[i]+nodes[i].len);changes+=y[i]!=nodes[i].y;}
  cout<<data.first<<" height "<<height<<" changes "<<changes<<endl;
 }
}
