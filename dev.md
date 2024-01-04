# dev diary

[29.12.23] Created a new project. Prepared initial structure, incl. basic dependencies
[30.12.23] Installed and configured Tailwind. Performed customization and wrote the first simple test for the React Vite page
[31.12.23] Added a custom hook for backend requests. While making tests for it, found out that 'render' from @testing-library/react-hooks is no longer supported in React 18. Instead, a renderHook has been added to @testing-library/react that does not have the waitForNextUpdate property. It is suggested to either use an earlier version of React or, for example, use Enzyme
<https://testing-library.com/docs/react-testing-library/api/#renderhook>
<https://github.com/testing-library/react-testing-library/pull/991>
[01.01.24] Reworked the tests for the custom hook useFetch by adding the helper function waitFor instead of the similar waitForNextUpdate. The console warns about actions performed on the tested component outside act(...), although from my point of view the code causing changes is actually wrapped exactly in act(...).
<https://www.toptal.com/react/testing-react-hooks-tutorial>
[02.01.24] User model added
[04.01.24] User router and controller added. mongodb-memory-server and supertest are used to mock db and test the server side
