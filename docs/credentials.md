# Credential Handling

jdbc-cli supports two ways to pass a database password without exposing it in process args or shell history.

## Option 1 — macOS Keychain (`--password-keychain`)

Store the password once:

```sh
security add-generic-password -s mydb -a myuser -w
# prompts for password, stores it in login keychain
```

Open a connection using the keychain entry:

```sh
jdbc-cli open --alias prod \
  --jdbc-url jdbc:mysql://db.example.com:3306/mydb \
  --user myuser \
  --password-keychain "mydb/myuser"
```

The format is `service/account` (account is optional if there's only one entry for the service).

**What happens under the hood:** The client sends only the service/account reference to the daemon. The daemon calls `security find-generic-password` to resolve the password — the client process never sees it, and it never appears in `ps` output.

## Option 2 — `op run` with 1Password (`--password-stdin`)

Create a `.env` file referencing a 1Password secret:

```
JDBC_PASSWORD=op://MyVault/MyDatabase/password
```

Open using stdin:

```sh
op run --env-file .env -- sh -c \
  'echo "$JDBC_PASSWORD" | jdbc-cli open --alias prod \
    --jdbc-url jdbc:mysql://db.example.com:3306/mydb \
    --user myuser \
    --password-stdin'
```

`op run` expands the secret into the child process environment. The password is piped via stdin, so it never appears in `ps auxww` output.

## Verification

After opening, confirm no password appears in process args:

```sh
ps auxww | grep jdbc
```

The output should show only the keychain reference or no password argument at all — never the literal secret.
