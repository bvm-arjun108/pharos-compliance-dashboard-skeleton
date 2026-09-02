# Pharos Compliance Dashboard — Quick AWS POC Deployment

This guide deploys the complete Pharos Compliance Dashboard to one temporary Amazon EC2 Linux machine for a demonstration or short operations-user test.

The deployment deliberately avoids production infrastructure. It does **not** require RDS, S3, Nginx, an Application Load Balancer, HTTPS, Route 53, or a CI/CD pipeline.

The EC2 machine runs all three components:

```text
Operations user's browser
        |
        | HTTP on port 4200
        v
Angular development server
        |
        | Local proxy
        v
Spring Boot on port 8085
        |
        | JDBC on localhost:5439
        v
PostgreSQL 16 Docker container
```

> This approach is intended only for a short-lived POC. The application currently has no user authentication, and the Angular development server is not a production web server.

## 1. What you need before starting

You need:

- Access to the AWS Console with permission to create an EC2 instance and security group.
- The public IP address of your computer.
- The public IP address of the operations user who will test the application.
- The project directory on your Mac:

  ```text
  /Users/arjunsharma/Desktop/Pharos Poc/pharos-compliance-dashboard-skeleton
  ```

- Approximately 30–45 minutes for the first setup and dependency downloads.

The operations user can find their public IP by searching for `what is my IP` in a browser. Record it in CIDR form by adding `/32`, for example:

```text
203.0.113.25/32
```

## 2. Create the project ZIP on your Mac

Open Terminal on your Mac and run:

```bash
cd "/Users/arjunsharma/Desktop/Pharos Poc"

zip -r pharos-dashboard.zip pharos-compliance-dashboard-skeleton \
  -x "*/node_modules/*" \
  -x "*/target/*" \
  -x "*/dist/*" \
  -x "*/.angular/*" \
  -x "*/.git/*"
```

The exclusions keep the ZIP small. Dependencies and compiled files will be recreated on the Linux machine.

Confirm that the ZIP exists:

```bash
ls -lh "/Users/arjunsharma/Desktop/Pharos Poc/pharos-dashboard.zip"
```

## 3. Launch the EC2 Linux machine using the AWS Console

1. Sign in to the AWS Console.
2. Confirm the desired AWS Region in the top-right corner.
3. Search for **EC2**.
4. Open **EC2**.
5. Select **Instances** in the left navigation.
6. Select **Launch instances**.

### 3.1 Name the instance

Under **Name and tags**, enter:

```text
pharos-poc
```

### 3.2 Select the operating system

Under **Application and OS Images**:

1. Select **Quick Start**.
2. Select **Amazon Linux**.
3. Choose the latest **Amazon Linux 2023 AMI**.
4. Use the `64-bit (x86)` architecture.

Do not select an ARM/Graviton instance for this guide because the commands below assume x86.

### 3.3 Select the instance size

Under **Instance type**, select:

```text
t3.medium
```

This provides enough memory for Maven, Angular compilation, Spring Boot, and PostgreSQL to run on the same machine. A smaller instance may become slow or run out of memory during compilation.

### 3.4 Create the key pair

Under **Key pair (login)**:

1. Select **Create new key pair**.
2. Enter the name:

   ```text
   pharos-poc-key
   ```

3. Key pair type: **RSA**.
4. Private key format: **.pem**.
5. Select **Create key pair**.

The browser downloads `pharos-poc-key.pem`. Store it securely. AWS does not allow the private key to be downloaded again later.

This guide assumes the file is in:

```text
$HOME/Downloads/pharos-poc-key.pem
```

### 3.5 Configure networking

Under **Network settings**, select **Edit**.

Configure:

- **VPC:** use the default VPC unless your AWS administrator requires another VPC.
- **Subnet:** choose a public subnet or leave **No preference** when using the default VPC.
- **Auto-assign public IP:** **Enable**.
- **Firewall:** choose **Create security group**.
- **Security group name:** `pharos-poc-sg`.
- **Description:** `Temporary access for the Pharos POC`.

Add the following inbound rules.

#### SSH access from your computer

```text
Type: SSH
Protocol: TCP
Port: 22
Source type: My IP
```

#### Dashboard access from your computer

```text
Type: Custom TCP
Protocol: TCP
Port: 4200
Source: <YOUR-PUBLIC-IP>/32
```

#### Dashboard access from the operations user

Select **Add security group rule** and add:

```text
Type: Custom TCP
Protocol: TCP
Port: 4200
Source: <OPERATIONS-USER-PUBLIC-IP>/32
```

Do **not** add public inbound rules for:

- Port `8085` — Spring Boot
- Port `5439` — host-side PostgreSQL
- Port `5432` — container-side PostgreSQL

Only the Angular server must be reachable from the internet. Angular proxies API calls to the backend on the same Linux machine.

### 3.6 Configure storage

Under **Configure storage**, set the root volume to:

```text
20 GiB gp3
```

### 3.7 Launch the instance

1. Review the summary.
2. Select **Launch instance**.
3. Select **View all instances**.
4. Wait until:

   ```text
   Instance state: Running
   Status check: 2/2 checks passed
   ```

## 4. Copy the EC2 public address

1. In **EC2 → Instances**, select `pharos-poc`.
2. Open the **Details** tab.
3. Copy the **Public IPv4 address**.

This guide refers to it as:

```text
<EC2-PUBLIC-IP>
```

For example:

```text
54.123.45.67
```

The automatically assigned public IP can change after stopping and starting the EC2 instance.

## 5. Upload the ZIP directly from your Mac

On your Mac, restrict the key permissions:

```bash
chmod 400 "$HOME/Downloads/pharos-poc-key.pem"
```

Upload the ZIP with `scp`:

```bash
scp \
  -i "$HOME/Downloads/pharos-poc-key.pem" \
  "/Users/arjunsharma/Desktop/Pharos Poc/pharos-dashboard.zip" \
  ec2-user@<EC2-PUBLIC-IP>:/home/ec2-user/
```

Replace `<EC2-PUBLIC-IP>` with the actual value. Example:

```bash
scp \
  -i "$HOME/Downloads/pharos-poc-key.pem" \
  "/Users/arjunsharma/Desktop/Pharos Poc/pharos-dashboard.zip" \
  ec2-user@54.123.45.67:/home/ec2-user/
```

The first connection may ask whether you trust the host. Type:

```text
yes
```

## 6. Connect to the EC2 Linux machine

From your Mac:

```bash
ssh \
  -i "$HOME/Downloads/pharos-poc-key.pem" \
  ec2-user@<EC2-PUBLIC-IP>
```

After connecting, the prompt should show that you are logged in as `ec2-user`.

## 7. Install the required software

Run the following inside the EC2 terminal:

```bash
sudo dnf update -y
```

Install Docker, Java 21, Maven, Node 24, npm, Python, and unzip:

```bash
sudo dnf install -y \
  docker \
  java-21-amazon-corretto-devel \
  maven \
  unzip \
  python3 \
  nodejs24 \
  nodejs24-npm
```

Select Node 24 as the active Node.js version:

```bash
sudo alternatives --set node /usr/bin/node-24
```

Start Docker now and automatically after reboot:

```bash
sudo systemctl enable --now docker
```

Verify the installations:

```bash
java -version
mvn -version
node -v
npm-24 -v
python3 --version
sudo docker version
```

Expected major versions:

```text
Java: 21
Node: 24
Python: 3
```

## 8. Extract the project

Create a POC directory:

```bash
sudo mkdir -p /opt/pharos-poc
sudo chown ec2-user:ec2-user /opt/pharos-poc
```

Extract the uploaded ZIP:

```bash
unzip /home/ec2-user/pharos-dashboard.zip -d /opt/pharos-poc
```

Enter the project directory:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton
```

Confirm that the expected directories exist:

```bash
ls
```

You should see at least:

```text
backend
frontend
database
docker-compose.yml
pom.xml
```

## 9. Start PostgreSQL in Docker

This command creates a PostgreSQL 16 container that matches the application's local database defaults:

```bash
sudo docker run -d \
  --name pharos-postgres \
  --restart unless-stopped \
  -e POSTGRES_USER=pharosRBT \
  -e POSTGRES_PASSWORD=pharosRBT \
  -e POSTGRES_DB=pharosRBT \
  -p 127.0.0.1:5439:5432 \
  -v pharos_postgres_data:/var/lib/postgresql/data \
  postgres:16
```

Important details:

- PostgreSQL is only exposed on `127.0.0.1`, not publicly.
- The backend connects to `localhost:5439`.
- The `pharos_postgres_data` Docker volume preserves the mock data across container restarts.
- `--restart unless-stopped` automatically restarts PostgreSQL after an EC2 reboot.

Wait approximately 10 seconds, then check the container:

```bash
sudo docker ps
```

Check the logs:

```bash
sudo docker logs pharos-postgres
```

The database is ready when the logs contain:

```text
database system is ready to accept connections
```

## 10. Create the Pharos database tables

From the project root:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton
```

Execute the DDL:

```bash
sudo docker exec -i pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  < database/sqlScripts/ddl.sql
```

Verify the tables:

```bash
sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "\dt pharos.*"
```

Run the DDL only when creating a new database volume. Do not repeatedly run it against an already initialized database unless the DDL is designed to tolerate existing objects.

## 11. Install `uv`

Install `uv` for the `ec2-user` account:

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Add it to the current terminal's PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Verify:

```bash
uv --version
```

If a future SSH session cannot find `uv`, run the `export PATH` command again.

## 12. Load the mock data

Make sure you are at the project root:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton
```

Optionally validate every file before loading:

```bash
uv run --project database/loaders \
  python3 database/loaders/load_record_transformation_journey.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_report_transformation_reconciliation.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit_exclusion_audit.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_report_group_config.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit_reconciliation.py --dry-run

uv run --project database/loaders \
  python3 database/loaders/load_reg_reportable_activity.py --dry-run
```

Load the data:

```bash
uv run --project database/loaders \
  python3 database/loaders/load_record_transformation_journey.py

uv run --project database/loaders \
  python3 database/loaders/load_report_transformation_reconciliation.py

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit_exclusion_audit.py

uv run --project database/loaders \
  python3 database/loaders/load_report_group_config.py

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit.py

uv run --project database/loaders \
  python3 database/loaders/load_rule_hit_reconciliation.py

uv run --project database/loaders \
  python3 database/loaders/load_reg_reportable_activity.py
```

The loaders perform upserts, so rerunning them should update matching rows rather than intentionally creating duplicate rows.

Verify the primary data counts:

```bash
sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.record_transformation_journey;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.report_transformation_reconciliation;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.rule_hit_exclusion_audit;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.report_group_config;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.rule_hit;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.rule_hit_reconciliation;"

sudo docker exec pharos-postgres \
  psql -U pharosRBT -d pharosRBT \
  -c "SELECT COUNT(*) FROM pharos.reg_reportable_activity;"
```

## 13. Build and start the Spring Boot backend

Enter the backend directory:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend
```

Build the backend:

```bash
mvn clean package -DskipTests
```

Start it in the background:

```bash
nohup java \
  -jar target/compliance-dashboard-api-1.0.0-SNAPSHOT.jar \
  > backend.log 2>&1 &

echo $! > backend.pid
```

Wait approximately 10 seconds and check its health:

```bash
curl http://localhost:8085/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Check the application health endpoint:

```bash
curl http://localhost:8085/api/v1/health
```

View the backend log:

```bash
tail -100 backend.log
```

The backend uses its existing defaults:

```text
URL: jdbc:postgresql://localhost:5439/pharosRBT
User: pharosRBT
Password: pharosRBT
```

No RDS environment variables are required for this POC.

## 14. Install and start the Angular frontend

Enter the frontend directory:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend
```

Install the exact dependencies from `package-lock.json`:

```bash
npm-24 ci
```

Start the Angular development server in the background and allow remote connections:

```bash
nohup npm-24 start -- --host 0.0.0.0 \
  > frontend.log 2>&1 &

echo $! > frontend.pid
```

Wait approximately 10 seconds, then inspect the log:

```bash
tail -100 frontend.log
```

The log should show that the development server is available on port `4200`.

Verify it locally from EC2:

```bash
curl -I http://localhost:4200
```

## 15. Open the application

From your computer, open:

```text
http://<EC2-PUBLIC-IP>:4200
```

Example:

```text
http://54.123.45.67:4200
```

Send the same URL to the operations user.

If the operations user cannot connect, confirm that their current public IP is present in the EC2 security group's port `4200` inbound rules.

## 16. Verify the POC before handing it over

Check:

1. The application opens using the EC2 public IP.
2. The dashboard status shows the backend as available.
3. KPI values display instead of `Unavailable`.
4. Country and report-group filters load.
5. Batch Explorer opens.
6. Report Config opens.
7. Transaction drill-down opens.
8. Browser refresh works on the dashboard.

On EC2, verify all processes:

```bash
sudo docker ps

ps -ef | grep -E "pharos-api|ng serve" | grep -v grep

curl http://localhost:8085/actuator/health

curl -I http://localhost:4200
```

## 17. View logs

### PostgreSQL

```bash
sudo docker logs --tail 100 pharos-postgres
```

Follow PostgreSQL logs continuously:

```bash
sudo docker logs -f pharos-postgres
```

### Backend

```bash
tail -100 /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend/backend.log
```

Follow backend logs continuously:

```bash
tail -f /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend/backend.log
```

### Frontend

```bash
tail -100 /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend/frontend.log
```

Follow frontend logs continuously:

```bash
tail -f /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend/frontend.log
```

Press `Ctrl+C` to stop following a log. This does not stop the application.

## 18. Stop and restart the applications

### Stop the backend

```bash
kill "$(cat /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend/backend.pid)"
```

### Start the backend again

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend

nohup java \
  -jar target/compliance-dashboard-api-1.0.0-SNAPSHOT.jar \
  > backend.log 2>&1 &

echo $! > backend.pid
```

### Stop the frontend

```bash
kill "$(cat /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend/frontend.pid)"
```

### Start the frontend again

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend

nohup npm-24 start -- --host 0.0.0.0 \
  > frontend.log 2>&1 &

echo $! > frontend.pid
```

### Stop PostgreSQL

```bash
sudo docker stop pharos-postgres
```

### Start PostgreSQL

```bash
sudo docker start pharos-postgres
```

Do not remove the `pharos_postgres_data` volume unless you intentionally want to erase all mock database data.

## 19. Restart after an EC2 reboot

Docker and PostgreSQL restart automatically because Docker is enabled and the container uses `--restart unless-stopped`.

The backend and frontend do not automatically restart in this simplified POC setup. After rebooting the EC2 instance, connect through SSH and run:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend

nohup java \
  -jar target/compliance-dashboard-api-1.0.0-SNAPSHOT.jar \
  > backend.log 2>&1 &

echo $! > backend.pid
```

Then:

```bash
cd /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend

nohup npm-24 start -- --host 0.0.0.0 \
  > frontend.log 2>&1 &

echo $! > frontend.pid
```

If the EC2 instance was stopped and started, copy its new public IP from the EC2 Console and provide the updated URL to the operations user.

## 20. Deploy a later version of the code

For another POC build:

1. Stop the backend and frontend.
2. Create a new ZIP on your Mac.
3. Upload it with `scp`.
4. Extract it into a new directory, such as `/opt/pharos-poc-v2`.
5. Build and start the backend and frontend from that directory.
6. Keep the existing `pharos-postgres` container and Docker volume if the schema has not changed.

The PostgreSQL data is independent of the extracted source-code directory.

## 21. Common troubleshooting

### SSH times out

Check:

- The EC2 instance is running.
- It has a public IPv4 address.
- Port `22` allows your current public IP.
- You are using `ec2-user`.
- The `.pem` file matches the instance's key pair.
- The key has `chmod 400` permissions.

### Browser cannot open port 4200

Check the EC2 security group and confirm port `4200` allows the viewer's current public IP.

On EC2, check whether Angular is listening:

```bash
ss -ltnp | grep 4200
```

Check the frontend log:

```bash
tail -100 /opt/pharos-poc/pharos-compliance-dashboard-skeleton/frontend/frontend.log
```

### Dashboard says the backend is unavailable

Check backend health:

```bash
curl http://localhost:8085/actuator/health
```

Check the backend log:

```bash
tail -100 /opt/pharos-poc/pharos-compliance-dashboard-skeleton/backend/backend.log
```

Confirm PostgreSQL is running:

```bash
sudo docker ps
```

### PostgreSQL container is not running

```bash
sudo docker start pharos-postgres
sudo docker logs --tail 100 pharos-postgres
```

### `uv` command not found

```bash
export PATH="$HOME/.local/bin:$PATH"
uv --version
```

### Node version is incorrect

```bash
sudo alternatives --set node /usr/bin/node-24
node -v
```

Use `npm-24` explicitly for frontend commands.

### Build runs out of memory

Confirm that the EC2 instance is at least `t3.medium`:

```bash
free -h
```

If necessary, stop the frontend while rebuilding the backend or temporarily resize the EC2 instance.

### Disk is full

```bash
df -h
sudo docker system df
```

Do not run Docker cleanup commands unless you understand which images, containers, and volumes they remove.

## 22. Stop or remove the POC after testing

### Keep it for another test

In the AWS Console:

1. Open **EC2 → Instances**.
2. Select `pharos-poc`.
3. Select **Instance state → Stop instance**.

Stopping the instance stops compute charges, but EBS storage charges continue. The automatically assigned public IP will normally change when the instance is started again.

### Permanently remove it

When the POC is no longer needed:

1. Open **EC2 → Instances**.
2. Select `pharos-poc`.
3. Select **Instance state → Terminate instance**.
4. Confirm termination.
5. Remove any unused security group created specifically for the POC.
6. Delete the downloaded private key if company policy requires it.

Termination removes the EC2 instance and normally deletes its root EBS volume. Treat termination as permanent.

## 23. Final quick-reference checklist

### One-time setup

- [ ] Create the project ZIP.
- [ ] Launch an Amazon Linux 2023 `t3.medium` instance.
- [ ] Allow SSH `22` from your IP.
- [ ] Allow dashboard `4200` from your IP and the operations user's IP.
- [ ] Upload the ZIP with `scp`.
- [ ] Install Docker, Java 21, Maven, Node 24, npm, Python, and unzip.
- [ ] Extract the project.
- [ ] Start PostgreSQL in Docker.
- [ ] Run `ddl.sql`.
- [ ] Install `uv`.
- [ ] Load all seven mock-data files.
- [ ] Build and start Spring Boot.
- [ ] Install dependencies and start Angular.
- [ ] Verify `http://<EC2-PUBLIC-IP>:4200`.

### Before each test session

- [ ] Confirm the EC2 instance is running.
- [ ] Confirm the public IP has not changed.
- [ ] Confirm the operations user's IP is allowed on port `4200`.
- [ ] Confirm PostgreSQL is running.
- [ ] Confirm the backend health is `UP`.
- [ ] Confirm Angular is listening on port `4200`.

### After testing

- [ ] Stop or terminate the EC2 instance.
- [ ] Remove temporary security-group access when it is no longer needed.
