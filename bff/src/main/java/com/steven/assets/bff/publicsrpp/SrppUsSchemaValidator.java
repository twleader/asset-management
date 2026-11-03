package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashSet;

/** Closed, dependency-free validator for the exported US OpenAPI JSON schema subset. */
final class SrppUsSchemaValidator {
 private static final JsonNode DOCUMENT=load();
 private SrppUsSchemaValidator(){}
 static void validate(JsonNode value){check(value,DOCUMENT,0);}
 private static JsonNode load(){try(var in=SrppUsSchemaValidator.class.getResourceAsStream("/srpp/us-report-schema.json")){if(in==null)throw new IOException("schema missing");return SrppDailyContextResponseValidator.parseStrict(in.readAllBytes());}catch(IOException e){throw new ExceptionInInitializerError(e);}}
 private static void check(JsonNode value,JsonNode schema,int depth){
  if(depth>100)fail();
  if(schema.has("$ref")){String ref=schema.path("$ref").asText();if(!ref.startsWith("#/"))fail();JsonNode target=DOCUMENT.at(ref.substring(1));if(target.isMissingNode())fail();check(value,target,depth+1);}
  for(String union:java.util.List.of("oneOf","anyOf"))if(schema.has(union)){int count=0;for(JsonNode variant:schema.get(union))try{check(value,variant,depth+1);count++;}catch(SrppPayloadException rejected){}if("oneOf".equals(union)?count!=1:count==0)fail();}
  if(schema.has("const")&&!schema.get("const").equals(value))fail();
  if(schema.has("enum")){boolean found=false;for(JsonNode option:schema.get("enum"))if(option.equals(value))found=true;if(!found)fail();}
  if(schema.has("type")){JsonNode type=schema.get("type");boolean ok=false;if(type.isArray()){for(JsonNode option:type)ok|=kind(value,option.asText());}else ok=kind(value,type.asText());if(!ok)fail();}
  if(value.isObject()){
   for(JsonNode required:schema.path("required"))if(!value.has(required.asText()))fail();
   var it=value.fields();while(it.hasNext()){var e=it.next();JsonNode known=schema.path("properties").get(e.getKey());if(known!=null)check(e.getValue(),known,depth+1);else if(schema.path("additionalProperties").isBoolean()&&!schema.path("additionalProperties").booleanValue())fail();else if(schema.path("additionalProperties").isObject())check(e.getValue(),schema.get("additionalProperties"),depth+1);}
  }
  if(value.isArray()){
   if(value.size()<schema.path("minItems").asInt(0)||value.size()>schema.path("maxItems").asInt(100000))fail();
   HashSet<String> seen=new HashSet<>();for(JsonNode item:value){if(schema.path("uniqueItems").asBoolean()&&!seen.add(SrppJcs.canonicalize(item)))fail();if(schema.has("items"))check(item,schema.get("items"),depth+1);}
  }
  if(value.isTextual()){
   String text=value.textValue();if(text.length()<schema.path("minLength").asInt(0)||text.length()>schema.path("maxLength").asInt(Integer.MAX_VALUE))fail();if(schema.has("pattern")&&!java.util.regex.Pattern.compile(schema.path("pattern").asText()).matcher(text).find())fail();
   try{switch(schema.path("format").asText()){case "date"->java.time.LocalDate.parse(text);case "date-time"->java.time.OffsetDateTime.parse(text);case "uuid"->{if(!java.util.UUID.fromString(text).toString().equals(text))fail();}default->{}}}catch(RuntimeException e){fail();}
  }
  if(value.isIntegralNumber()&&(!value.canConvertToLong()||Math.abs(value.longValue())>9007199254740991L||value.longValue()<schema.path("minimum").asLong(-9007199254740991L)||value.longValue()>schema.path("maximum").asLong(9007199254740991L)))fail();
 }
 private static boolean kind(JsonNode value,String type){return switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "boolean"->value.isBoolean();case "null"->value.isNull();default->false;};}
 private static void fail(){throw new SrppPayloadException("US schema rejected response");}
}
