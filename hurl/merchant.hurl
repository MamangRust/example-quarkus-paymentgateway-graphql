### =====================================
### PRE-REQ: LOGIN
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json

{
  "query": "mutation Login($input: LoginRequest!) { login(input: $input) { accessToken } }",
  "variables": {
    "input": {
      "username": "hurl_test_user",
      "password": "Password123!"
    }
  }
}

HTTP 200

[Captures]
access_token: jsonpath "$.data.login.accessToken"


### =====================================
### CREATE MERCHANT
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateMerchant($req: CreateMerchantRequest!) { createMerchant(req: $req) { merchantId name apiKey apiSecret status } }",
  "variables": {
    "req": {
      "name": "Hurl Merchant",
      "userId": 1
    }
  }
}

HTTP 200

[Captures]
merchant_id: jsonpath "$.data.createMerchant.merchantId"
api_key: jsonpath "$.data.createMerchant.apiKey"


### =====================================
### GET MERCHANT BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindMerchant($merchantId: BigInteger!) { findMerchantById(id: $merchantId) { merchantId name status apiKey } }",
  "variables": {
    "merchantId": {{merchant_id}}
  }
}

HTTP 200


### =====================================
### UPDATE MERCHANT
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation UpdateMerchant($req: UpdateMerchantRequest!) { updateMerchant(req: $req) { merchantId name status } }",
  "variables": {
    "req": {
      "merchantId": {{merchant_id}},
      "name": "Hurl Merchant Updated",
      "userId": 1,
      "status": "ACTIVE"
    }
  }
}

HTTP 200


### =====================================
### GET MONTHLY TRANS AMOUNT STATS BY APKEY
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query MonthAmountApiKey($req: MonthYearAmountApiKey!) { getMonthlyAmountByApiKey(req: $req) { month amount } }",
  "variables": {
    "req": {
      "apiKey": "{{api_key}}",
      "year": 2026
    }
  }
}

HTTP 200


### =====================================
### TRASH MERCHANT
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashMerchant($merchantId: BigInteger!) { trashMerchant(id: $merchantId) { merchantId } }",
  "variables": {
    "merchantId": {{merchant_id}}
  }
}

HTTP 200


### =====================================
### RESTORE MERCHANT
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreMerchant($merchantId: BigInteger!) { restoreMerchant(id: $merchantId) { merchantId } }",
  "variables": {
    "merchantId": {{merchant_id}}
  }
}

HTTP 200
