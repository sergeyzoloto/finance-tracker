import { LoginForm } from "../../components/LoginForm";

const WelcomePage = () => {
  return (
    <div className="welcome-page">
      <h1>Welcome to finance-tracker</h1>
      <p>Please log in to continue.</p>
      <LoginForm />
    </div>
  );
};

export default WelcomePage;
