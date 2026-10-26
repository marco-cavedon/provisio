// End-to-end check of MongoDB Search and Vector Search on the local stack, run as the application user:
// it also proves that `provisio` can create search indexes on its own database.
// Run with (mongosh prompts for MONGODB_APP_PASSWORD):
//   docker compose exec mongodb mongosh -u provisio -p --authenticationDatabase admin --quiet \
//     --file /scripts/verify-search.js
// Uses a throwaway collection in the provisio database, dropped at the end.

const check = { docs: db.getSiblingDB("provisio").getCollection("infra_check") };
check.docs.drop();

check.docs.insertMany([
  { text: "Supplier A guarantees delivery in 5 working days", embedding: [0.9, 0.1, 0.0] },
  { text: "Supplier B requires a minimum order of 500 units", embedding: [0.1, 0.9, 0.0] },
  { text: "Supplier C reported a two-week delay", embedding: [0.0, 0.1, 0.9] },
]);

check.docs.createSearchIndex("text_index", { mappings: { dynamic: true } });
check.docs.createSearchIndex("vector_index", "vectorSearch", {
  fields: [{ type: "vector", path: "embedding", numDimensions: 3, similarity: "cosine" }],
});

function waitUntilQueryable(name) {
  for (let i = 0; i < 120; i++) {
    const [index] = check.docs.getSearchIndexes(name);
    if (index && index.queryable) return;
    sleep(1000);
  }
  throw new Error(`Search index ${name} not queryable after 120s`);
}
waitUntilQueryable("text_index");
waitUntilQueryable("vector_index");

const fullText = check.docs.aggregate([
  { $search: { index: "text_index", text: { query: "delay", path: "text" } } },
  { $project: { _id: 0, text: 1 } },
]).toArray();

const vector = check.docs.aggregate([
  { $vectorSearch: { index: "vector_index", path: "embedding", queryVector: [0.0, 0.2, 0.8], numCandidates: 10, limit: 1 } },
  { $project: { _id: 0, text: 1 } },
]).toArray();

check.docs.drop();

if (fullText.length !== 1 || !fullText[0].text.includes("delay")) throw new Error(`$search failed: ${JSON.stringify(fullText)}`);
if (vector.length !== 1 || !vector[0].text.includes("delay")) throw new Error(`$vectorSearch failed: ${JSON.stringify(vector)}`);

print("OK: $search and $vectorSearch both returned the expected document.");
