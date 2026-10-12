import { core, type Identity } from "@/lib/api";
export default async function SessionControl() {
  const user = await core<Identity>("/me");
  if (!user.data) return <span className="badge">Local development</span>;
  return (
    <form action="/backend/logout" method="post">
      <input type="hidden" name="_csrf" value={user.data.csrfToken} />
      <button className="secondary" type="submit">
        Sign out
      </button>
    </form>
  );
}
