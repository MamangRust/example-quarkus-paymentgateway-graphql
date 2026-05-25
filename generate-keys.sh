#!/bin/bash

KEYS_DIR="src/main/resources/META-INF/resources"
mkdir -p "$KEYS_DIR"

echo "Generating RSA private key..."
openssl genrsa -out "$KEYS_DIR/privateKey.pem" 2048

echo "Extracting RSA public key..."
openssl rsa -in "$KEYS_DIR/privateKey.pem" -pubout -out "$KEYS_DIR/publicKey.pem"

echo "Keys generated successfully:"
echo "  Private key: $KEYS_DIR/privateKey.pem"
echo "  Public key:  $KEYS_DIR/publicKey.pem"
