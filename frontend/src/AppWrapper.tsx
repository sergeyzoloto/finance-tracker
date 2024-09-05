import PropTypes from "prop-types";
import { BrowserRouter as Router } from "react-router-dom";

/**
 * This component wraps our App with the providers
 * that we do not want to have in our tests
 */
function AppWrapper({ children }: { readonly children: React.ReactNode }) {
  return <Router>{children}</Router>;
}

AppWrapper.propTypes = {
  children: PropTypes.element.isRequired,
};

export default AppWrapper;
