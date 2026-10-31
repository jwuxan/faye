// Scores a sign in with the ip-reputation service: above 50 is high risk.
//
// If the service fails or does not answer in time, the sign in counts as high risk. Failing closed
// costs the person a second factor, never the sign in.
export default async function (ctx, cap) {
  try {
    const reputation = await cap.http.getJson('ip-reputation', {
      ip: ctx.request.ip ?? '',
      user_agent: ctx.request.user_agent ?? '',
    })
    return { set: { risk: reputation.score > 50 ? 'high' : 'low' } }
  } catch (e) {
    cap.log.info('ip-reputation unavailable, scoring high:', e.message)
    return { set: { risk: 'high' } }
  }
}
