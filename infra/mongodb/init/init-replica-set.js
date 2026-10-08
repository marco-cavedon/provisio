// One-shot initialisation, run by the mongodb-init service. Idempotent: safe on every `docker compose up`.

// 1. Replica set (single node). The member host is the Compose service name.
try {
  rs.status();
  print("Replica set already initiated");
} catch (e) {
  if (e.codeName !== "NotYetInitialized") throw e;
  rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "mongodb:27017" }] });
  print("Replica set initiated");
}

// Wait until this node is primary: users can only be created on the primary.
for (let i = 0; i < 60 && !db.hello().isWritablePrimary; i++) sleep(1000);
if (!db.hello().isWritablePrimary) throw new Error("Node did not become primary within 60s");

// 2. User for mongot, with the searchCoordinator role required to sync indexes.
const password = process.env.MONGOT_PASSWORD;
if (!password) throw new Error("MONGOT_PASSWORD is not set");

const admin = db.getSiblingDB("admin");
if (admin.getUser("mongot")) {
  admin.updateUser("mongot", { pwd: password, roles: ["searchCoordinator"] });
  print("User mongot updated");
} else {
  admin.createUser({ user: "mongot", pwd: password, roles: ["searchCoordinator"] });
  print("User mongot created");
}
