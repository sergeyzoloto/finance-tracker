export function isValidEmail(email: string): boolean {
  if (!email || email.trim() === "") {
    return false;
  }
  const emailRegex = /^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
  return emailRegex.test(email);
}
