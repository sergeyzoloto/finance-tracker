const getCookieValue = (cookieName: string): string | null => {
  const nameString = cookieName + "=";
  const value = document.cookie
    .split("; ")
    .find((row) => row.startsWith(nameString));
  return value ? value.split("=")[1] : null;
};

export default getCookieValue;
