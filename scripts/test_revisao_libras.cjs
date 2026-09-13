const {test} = require("node:test");
const assert = require("node:assert/strict");
const {validateDecision, validateImport} = require("./revisao_libras.js");
const item = {key:"nome:abcdefghijk",sha256:"abc",technical:{duration:5},file_issue:null};
const pending = {linguistic_status:"pendente",permission_status:"pendente"};
const approved = {...pending,linguistic_status:"aprovado",reviewer:"Consultor",confirmed_label:"nome",region:"a verificar em Goiânia",start:0,end:5};
const queue = {queue_id:"fixture",candidates:[item]};
test("pending never becomes training ready", () => assert.equal(validateDecision({...pending,training_ready:true},item).training_ready,false));
test("approval requires reviewer label region and valid bounds", () => {
  for(const change of [{reviewer:""},{confirmed_label:""},{region:""},{start:-1},{end:6},{end:0},{start:null},{end:NaN}]) assert.throws(()=>validateDecision({...approved,...change},item));
  assert.equal(validateDecision(approved,item).permission_status,"pendente");
});
test("rejection requires reason", () => assert.throws(()=>validateDecision({...approved,linguistic_status:"rejeitado",notes:""},item)));
test("missing media blocks linguistic approval", () => assert.throws(()=>validateDecision(approved,{...item,file_issue:"missing"})));
test("permission independently requires evidence scope and reviewer", () => {
  assert.throws(()=>validateDecision({...approved,permission_status:"documentada"},item));
  const result=validateDecision({...approved,permission_status:"documentada",permission_evidence:"record-1",permission_scope:"research",permission_reviewer:"Reviewer"},item);
  assert.equal(result.training_ready,false);
});
test("import checks queue hash known keys and duplicates", () => {
  const decision=validateDecision(approved,item);
  const payload={schema_version:1,queue_id:"fixture",decisions:[decision]};
  assert.equal(validateImport(payload,queue)[item.key].training_ready,false);
  for(const change of [{queue_id:"old"},{decisions:[{...decision,sha256:"changed"}]},{decisions:[{...decision,key:"unknown"}]},{decisions:[decision,decision]}]) assert.throws(()=>validateImport({...payload,...change},queue));
});