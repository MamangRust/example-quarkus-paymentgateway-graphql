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
### CREATE SALDO
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateSaldo($req: CreateSaldoRequest!) { createSaldo(req: $req) { saldoId cardNumber totalBalance } }",
  "variables": {
    "req": {
      "cardNumber": "1234567890123456",
      "totalBalance": 500000
    }
  }
}

HTTP 200

[Captures]
saldo_id: jsonpath "$.data.createSaldo.saldoId"


### =====================================
### GET SALDO BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindSaldo($saldoId: BigInteger!) { findSaldoById(id: $saldoId) { saldoId cardNumber totalBalance } }",
  "variables": {
    "saldoId": {{saldo_id}}
  }
}

HTTP 200


### =====================================
### UPDATE SALDO
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation UpdateSaldo($req: UpdateSaldoRequest!) { updateSaldo(req: $req) { saldoId cardNumber totalBalance } }",
  "variables": {
    "req": {
      "saldoId": {{saldo_id}},
      "cardNumber": "1234567890123456",
      "totalBalance": 750000
    }
  }
}

HTTP 200


### =====================================
### TRASH SALDO
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashSaldo($saldoId: BigInteger!) { trashSaldo(id: $saldoId) { saldoId } }",
  "variables": {
    "saldoId": {{saldo_id}}
  }
}

HTTP 200


### =====================================
### RESTORE SALDO
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreSaldo($saldoId: BigInteger!) { restoreSaldo(id: $saldoId) { saldoId } }",
  "variables": {
    "saldoId": {{saldo_id}}
  }
}

HTTP 200
