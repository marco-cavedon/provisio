// One-shot initialisation, run by the mongodb-init service. Idempotent: safe on every `docker compose up`.
//
// Access control is enabled on mongod. On a fresh volume no user exists yet, so the first one (admin) is created
// through the localhost exception: mongodb-init shares mongod's network namespace and connects via localhost.
// The exception closes as soon as the first user exists; from then on this script authenticates as admin.

const ADMIN_USER = "admin";
const APP_USER = "provisio";
const APP_DB = "provisio";

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}
const adminPassword = requiredEnv("MONGODB_ADMIN_PASSWORD");
const appPassword = requiredEnv("MONGODB_APP_PASSWORD");
const mongotPassword = requiredEnv("MONGOT_PASSWORD");

const admin = db.getSiblingDB("admin");

function isUnauthorized(e) {
  return e.code === 13 || e.codeName === "Unauthorized";
}

function authenticateAsAdmin() {
  try {
    admin.auth(ADMIN_USER, adminPassword);
  } catch (e) {
    throw new Error(
      `Cannot authenticate as ${ADMIN_USER}: ${e.message}. ` +
      "Either MONGODB_ADMIN_PASSWORD changed, or the volume predates access control " +
      "(users exist but no admin was ever created). For local data, recreate it: docker compose down -v. " +
      "See infra/README.md."
    );
  }
}

// 1. Replica set (single node). The member host is the Compose service name.
//    rs.status() succeeds without credentials only while the localhost exception is open, i.e. no user exists.
let localhostException = true;
try {
  rs.status();
  print("Replica set already initiated");
} catch (e) {
  if (e.codeName === "NotYetInitialized") {
    rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "mongodb:27017" }] });
    print("Replica set initiated");
  } else if (isUnauthorized(e)) {
    localhostException = false;
    authenticateAsAdmin();
    rs.status();
    print("Replica set already initiated");
  } else {
    throw e;
  }
}

// Wait until this node is primary: users can only be created on the primary.
for (let i = 0; i < 60 && !db.hello().isWritablePrimary; i++) sleep(1000);
if (!db.hello().isWritablePrimary) throw new Error("Node did not become primary within 60s");

// 2. Administrator, created through the localhost exception on a fresh volume.
if (localhostException) {
  admin.createUser({ user: ADMIN_USER, pwd: adminPassword, roles: ["root"] });
  print(`User ${ADMIN_USER} created`);
  authenticateAsAdmin();
}

// 3. Service users, created or updated so that password changes in .env are applied on the next start.
function upsertUser(user, password, roles) {
  if (admin.getUser(user)) {
    admin.updateUser(user, { pwd: password, roles });
    print(`User ${user} updated`);
  } else {
    admin.createUser({ user, pwd: password, roles });
    print(`User ${user} created`);
  }
}

// mongot: syncs search indexes through change streams.
upsertUser("mongot", mongotPassword, ["searchCoordinator"]);

// Orchestrator: read and write on its own database only. Granted on the whole database, not per collection:
// readWrite scoped to single collections is reported not to cover createSearchIndexes.
upsertUser(APP_USER, appPassword, [{ role: "readWrite", db: APP_DB }]);
